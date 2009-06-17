// Copyright (C) 2007 Google Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.caja.parser.html;

import com.google.caja.lexer.FilePosition;
import com.google.caja.lexer.HtmlTokenType;
import com.google.caja.lexer.Token;
import com.google.caja.reporting.Message;
import com.google.caja.reporting.MessageLevel;
import com.google.caja.reporting.MessagePart;
import com.google.caja.reporting.MessageQueue;
import com.google.caja.util.Name;
import com.google.caja.util.Strings;

import java.util.List;

import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.DocumentFragment;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.Text;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import nu.validator.htmlparser.common.DoctypeExpectation;
import nu.validator.htmlparser.impl.Tokenizer;

/**
 * A bridge between DomParser and html5lib which translates
 * {@code Token<HtmlTokenType>}s into SAX style events which are fed to the
 * TreeBuilder.  The TreeBuilder responds by issuing {@code createElement}
 * commands which are used to build a {@link DocumentFragment}.
 *
 * @author mikesamuel@gmail.com
 */
public class Html5ElementStack implements OpenElementStack {
  private final CajaTreeBuilder builder;
  private final char[] charBuf = new char[1024];
  private final MessageQueue mq;
  private final Document doc;
  private final boolean needsDebugData;
  private boolean isFragment;

  /**
   * @param needsDebugData see {@link DomParser#setNeedsDebugData(boolean)}
   * @param queue will receive error messages from html5lib.
   */
  Html5ElementStack(Document doc, boolean needsDebugData, MessageQueue queue) {
    this.doc = doc;
    this.needsDebugData = needsDebugData;
    this.mq = queue;
    builder = new CajaTreeBuilder(doc, needsDebugData, mq);
  }

  public final Document getDocument() { return doc; }

  /** @inheritDoc */
  public void open(boolean isFragment) {
    this.isFragment = isFragment;
    builder.setDoctypeExpectation(DoctypeExpectation.NO_DOCTYPE_ERRORS);
    try {
      builder.start(new Tokenizer(builder));
    } catch (SAXException ex) {
      throw new RuntimeException(ex);
    }
    builder.setErrorHandler(
        new ErrorHandler() {
          private FilePosition lastPos;
          private String lastMessage;

          public void error(SAXParseException ex) {
            // htmlparser is a bit strident, so we lower it's warnings to
            // MessageLevel.LINT.
            report(MessageLevel.LINT, ex);
          }
          public void fatalError(SAXParseException ex) {
            report(MessageLevel.FATAL_ERROR, ex);
          }
          public void warning(SAXParseException ex) {
            report(MessageLevel.LINT, ex);
          }

          private void report(MessageLevel level, SAXParseException ex) {
            String message = errorMessage(ex);
            FilePosition pos = builder.getErrorLocation();
            if (message.equals(lastMessage) && pos.equals(lastPos)) { return; }
            lastMessage = message;
            lastPos = pos;
            mq.getMessages().add(new Message(
                DomParserMessageType.GENERIC_SAX_ERROR, level, pos,
                MessagePart.Factory.valueOf(message)));
          }

          private String errorMessage(SAXParseException ex) {
            // Don't ask.
            return ex.getMessage()
                .replace('\u201c', '\'').replace('\u201d', '\'');
          }
        });
  }

  /** @inheritDoc */
  public void finish(FilePosition endOfFile) {
    builder.finish(endOfFile);
    builder.closeUnclosedNodes();
  }

  /** @inheritDoc */
  public Name canonicalizeElementName(String elementName) {
    return Name.html(elementName);
  }

  /** @inheritDoc */
  public Name canonicalizeAttributeName(String attributeName) {
    return Name.html(attributeName);
  }

  public static String canonicalElementName(String elementName) {
    // forces LANG=C like behavior.
    return Strings.toLowerCase(elementName);
  }

  public static String canonicalAttributeName(String attributeName) {
    // forces LANG=C like behavior.
    return Strings.toLowerCase(attributeName);
  }

  /** @inheritDoc */
  public DocumentFragment getRootElement() {
    // libHtmlParser always produces a document with html, head, and body tags
    // which we usually don't want, so unroll it.

    // If we can't throw away the head element, and the body header, then we
    // return the entire document.  Otherwise, we return a document fragment
    // consisting of the contents of the body.

    Element root = builder.getRootElement();
    DocumentFragment result = doc.createDocumentFragment();
    if (needsDebugData) {
      Nodes.setFilePositionFor(result, builder.getFragmentBounds());
    }
    if (!isFragment) {
      result.appendChild(root);
      return result;
    }

    final Node first = root.getFirstChild();

    // If disposing of the html, body, or head elements would lose info don't
    // do it, so look for attributes.
    boolean tagsBesidesHeadBodyFrameset = false;
    boolean topLevelTagsWithAttributes = hasSpecifiedAttributes(root);

    for (Node child = first; child != null; child = child.getNextSibling()) {
      if (child instanceof Element) {
        Element el = (Element) child;
        String tagName = el.getTagName();
        if (!("head".equals(tagName) || "body".equals(tagName)
              || "frameset".equals(tagName))) {
          tagsBesidesHeadBodyFrameset = true;
          break;
        }
        if (!topLevelTagsWithAttributes
            && hasSpecifiedAttributes(el)
            // framesets, unlike body elements, are never created out of whole
            // cloth, so we do not behave differently when there is a frameset
            // with attributes.
            && !"frameset".equals(tagName)) {
          topLevelTagsWithAttributes = true;
        }
      }
    }

    // topLevelTagsWithAttributes is true in the following cases
    //   <html xml:lang="en">...</html>
    //   <html><body bgcolor=white>...</body></html>
    // tagsBesidesHeadAndBody is true for
    //   <html><frameset>...</frameset></html>
    if (tagsBesidesHeadBodyFrameset || topLevelTagsWithAttributes) {
      // Merging the body and head would lose info.
      result.appendChild(root);
      return result;
    }

    // Merge the body and head into a fragment.
    // Convert
    // <html>
    //   <head>
    //     <link rel=stylesheet ...>
    //   </head>
    //   <body>
    //     <p>Hello World</p.
    //   </body>
    // </html>
    // to
    // #fragment
    //   <link rel=stylesheet ...>
    //   <p>Hello World</p.

    Node pending = null;
    for (Node child = first; child != null; child = child.getNextSibling()) {
      if (child instanceof Element) {
        String tagName = ((Element) child).getTagName();
        if ("head".equals(tagName) || "body".equals(tagName)) {
          // Shallow descent
          for (Node grandchild = child.getFirstChild(); grandchild != null;
               grandchild = grandchild.getNextSibling()) {
            pending = appendNormalized(pending, grandchild, result);
          }
        } else {  // reached for framesets
          pending = child;
        }
      } else {
        pending = appendNormalized(pending, child, result);
      }
    }
    if (pending != null) { result.appendChild(pending); }

    return result;
  }

  private static boolean hasSpecifiedAttributes(Element el) {
    NamedNodeMap attrs = el.getAttributes();
    for (int i = 0, n = attrs.getLength(); i < n; ++i) {
      Attr a = (Attr) attrs.item(i);
      if (el.hasAttribute(a.getName())) {
        return true;
      }
    }
    return false;
  }

  /**
   * Given one or two nodes, see if the two can be combined.
   * If two are passed in, they might be combined into one and returned, or
   * the first will be appended to parent, and the other returned.
   */
  private Node appendNormalized(
      Node pending, Node current, DocumentFragment parent) {
    if (pending == null) { return current; }
    if (pending.getNodeType() != Node.TEXT_NODE
        || current.getNodeType() != Node.TEXT_NODE) {
      parent.appendChild(pending);
      return current;
    }
    Text a = (Text) pending, b = (Text) current;
    Text combined = doc.createTextNode(a.getTextContent() + b.getTextContent());
    if (needsDebugData) {
      Nodes.setFilePositionFor(
          combined,
          FilePosition.span(
              Nodes.getFilePositionFor(a),
              Nodes.getFilePositionFor(b)));
      Nodes.setRawText(combined, Nodes.getRawText(a) + Nodes.getRawText(b));
    }
    return combined;
  }

  /**
   * Records the fact that a tag has been seen, updating internal state
   *
   * @param start the token of the beginning of the tag, so {@code "<p"} for a
   *   paragraph start, {@code "</p"} for an end tag.
   * @param end the token of the beginning of the tag, so {@code ">"} for a
   *   paragraph start, {@code "/>"} for an unary break tag.
   * @param attrs the attributes for the element.  This will be empty
   *   for end tags.
   */
  public void processTag(Token<HtmlTokenType> start, Token<HtmlTokenType> end,
                         List<Attr> attrs) {
    builder.setTokenContext(start, end);
    try {
      String tagName = CajaTreeBuilder.tagName(start.text);
      if (CajaTreeBuilder.isEndTag(start.text)) {
        builder.endTag(tagName, new CajaTreeBuilder.AttributesImpl(attrs));
      } else {
        builder.startTag(tagName, new CajaTreeBuilder.AttributesImpl(attrs));
      }
    } catch (SAXException ex) {
      throw new RuntimeException(ex);
    }
  }

  /**
   * Adds the given text node to the DOM.
   */
  public void processText(Token<HtmlTokenType> textToken) {
    // htmlparser doesn't recognize \r as whitespace.
    String text = textToken.text.replaceAll("\r\n?", "\n");
    if (!text.equals(textToken.text)) {
      textToken = Token.instance(text, textToken.type, textToken.pos);
    }
    char[] chars;
    int n = text.length();
    if (n <= charBuf.length) {
      chars = charBuf;
      text.getChars(0, n, chars, 0);
    } else {
      chars = text.toCharArray();
    }
    builder.setTokenContext(textToken, textToken);
    try {
      builder.characters(chars, 0, n);
    } catch (SAXException ex) {
      throw new RuntimeException(ex);
    }
  }
}
