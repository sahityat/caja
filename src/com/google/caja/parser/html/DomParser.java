// Copyright (C) 2006 Google Inc.
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

import com.google.caja.lexer.CharProducer;
import com.google.caja.lexer.FilePosition;
import com.google.caja.lexer.HtmlLexer;
import com.google.caja.lexer.HtmlTokenType;
import com.google.caja.lexer.InputSource;
import com.google.caja.lexer.ParseException;
import com.google.caja.lexer.Token;
import com.google.caja.lexer.TokenQueue;
import com.google.caja.lexer.TokenStream;
import com.google.caja.reporting.Message;
import com.google.caja.reporting.MessagePart;
import com.google.caja.reporting.MessageQueue;
import com.google.caja.reporting.MessageType;
import com.google.caja.util.Name;
import com.google.caja.util.Strings;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.regex.Pattern;

import org.w3c.dom.Attr;
import org.w3c.dom.DOMException;
import org.w3c.dom.DOMImplementation;
import org.w3c.dom.Document;
import org.w3c.dom.DocumentFragment;
import org.w3c.dom.DocumentType;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.bootstrap.DOMImplementationRegistry;

/**
 * Parses a {@link Node} from a stream of XML or HTML tokens.
 * This is a non-validating parser, that will parse tolerantly when created
 * in HTML mode, or will require balanced tags when created in XML mode.
 * <p>
 * Since it's not validating, we don't bother to parse DTDs, and so does not
 * process external entities.  Parsing will not cause URI resolution or
 * fetching.
 *
 * @author mikesamuel@gmail.com
 */
public final class DomParser {
  private final TokenQueue<HtmlTokenType> tokens;
  private final boolean asXml;
  private final MessageQueue mq;
  private boolean needsDebugData = true;

  public DomParser(TokenQueue<HtmlTokenType> tokens, boolean asXml,
                   MessageQueue mq) {
    this.tokens = tokens;
    this.asXml = asXml;
    this.mq = mq;
  }

  /**
   * Guesses the markup type -- HTML vs XML -- by looking at the first token.
   */
  public DomParser(HtmlLexer lexer, InputSource src, MessageQueue mq)
      throws ParseException {
    this.mq = mq;
    LookaheadLexer la = new LookaheadLexer(lexer);
    lexer.setTreatedAsXml(this.asXml = guessAsXml(la, src));
    this.tokens = new TokenQueue<HtmlTokenType>(la, src);
  }

  public TokenQueue<HtmlTokenType> getTokenQueue() { return tokens; }

  public boolean asXml() { return asXml; }

  /**
   * Sets a flag which determines whether subsequent parse calls will attach
   * file positions and raw text to DOM nodes.
   * {@link org.w3c.dom.Node#setUserData} is very slow in some implementations,
   * so if a client does not need File Position or raw text information, then
   * it can call this with {@code false} as the argument to improve parsing
   * performance.
   */
  public void setNeedsDebugData(boolean needsDebugData) {
    this.needsDebugData = needsDebugData;
  }

  protected OpenElementStack makeElementStack(Document doc, MessageQueue mq) {
    return asXml
        ? OpenElementStack.Factory.createXmlElementStack(
            doc, needsDebugData, mq)
        : OpenElementStack.Factory.createHtml5ElementStack(
            doc, needsDebugData, mq);
  }

  public static Document makeDocument(DocumentType doctype, String features) {
    if (features == null) { features = "XML 1.0 Traversal"; }
    DOMImplementation impl;
    try {
      impl = DOMImplementationRegistry.newInstance()
          .getDOMImplementation(features);
    } catch (ClassNotFoundException ex) {
      throw new RuntimeException(
          "Missing DOM implementation.  Is Xerces on the classpath?", ex);
    } catch (IllegalAccessException ex) {
      throw new RuntimeException(
          "Missing DOM implementation.  Is Xerces on the classpath?", ex);
    } catch (InstantiationException ex) {
      throw new RuntimeException(
          "Missing DOM implementation.  Is Xerces on the classpath?", ex);
    }
    return impl.createDocument(null, null, doctype);
  }

  /** Parse a document returning the document element. */
  public Element parseDocument() throws ParseException {
    return parseDocument(null);
  }

  /** Parse a document returning the document element. */
  public Element parseDocument(String features) throws ParseException {
    Document doc = makeDocument(null, features);
    OpenElementStack elementStack = makeElementStack(doc, mq);

    // Make sure the elementStack is empty.
    elementStack.open(false);

    do {
      Token<HtmlTokenType> t = tokens.peek();
      if (HtmlTokenType.TEXT == t.type) {
        if (!"".equals(t.text.trim())) { break; }
      } else if (HtmlTokenType.COMMENT != t.type
                 && HtmlTokenType.DIRECTIVE != t.type) {
        break;
      }
      tokens.advance();
      continue;
    } while (!tokens.isEmpty());

    do {
      parseDom(elementStack);
    } while (!tokens.isEmpty());

    FilePosition endPos = FilePosition.endOf(tokens.lastPosition());
    try {
      elementStack.finish(endPos);
    } catch (IllegalDocumentStateException ex) {
      throw new ParseException(ex.getCajaMessage(), ex);
    }

    DocumentFragment root = elementStack.getRootElement();
    Node firstChild = root.getFirstChild();
    if (firstChild == null || firstChild.getNodeType() != Node.ELEMENT_NODE) {
      throw new ParseException(new Message(
          DomParserMessageType.MISSING_DOCUMENT_ELEMENT, endPos));
    }

    // Check that there isn't any extraneous content after the root element.
    for (Node child = firstChild.getNextSibling(); child != null;
         child = child.getNextSibling()) {
      switch (child.getNodeType()) {
        case Node.COMMENT_NODE:
        case Node.DOCUMENT_TYPE_NODE:
          continue;
        case Node.TEXT_NODE:
          if ("".equals(child.getNodeValue().trim())) { continue; }
          break;
        default: break;
      }
      throw new ParseException(new Message(
          DomParserMessageType.MISPLACED_CONTENT,
          Nodes.getFilePositionFor(child)));
    }

    doc.appendChild(firstChild);

    return (Element) firstChild;
  }

  /** Parses a snippet of markup. */
  public DocumentFragment parseFragment(Document doc) throws ParseException {
    OpenElementStack elementStack = makeElementStack(doc, mq);

    // Make sure the elementStack is empty.
    elementStack.open(true);

    while (!tokens.isEmpty()) {
      // Skip over top level comments, and whitespace only text nodes.
      // Whitespace is significant for XML unless the schema specifies
      // otherwise, but whitespace outside the root element is not.  There is
      // one exception for whitespace preceding the prologue.
      Token<HtmlTokenType> t = tokens.peek();

      switch (t.type) {
        case COMMENT:
        case DIRECTIVE:  // especially DOCTYPEs
          tokens.advance();
          continue;
        default: break;
      }

      parseDom(elementStack);
    }

    FilePosition endPos = tokens.lastPosition();
    if (endPos != null) {
      endPos = FilePosition.endOf(endPos);
    } else {  // No lastPosition if the queue was empty.
      endPos = FilePosition.startOfFile(tokens.getInputSource());
    }
    try {
      elementStack.finish(endPos);
    } catch (IllegalDocumentStateException ex) {
      throw new ParseException(ex.getCajaMessage(), ex);
    }

    return elementStack.getRootElement();
  }

  /**
   * Creates a TokenQueue suitable for this class's parse methods.
   * @param asXml true to parse as XML, false as HTML.
   * @param in closed by this method.
   * @throws IOException when in raises an exception during read.
   */
  public static TokenQueue<HtmlTokenType> makeTokenQueue(
      InputSource is, Reader in, boolean asXml) throws IOException {
    return makeTokenQueue(FilePosition.startOfFile(is), in, asXml);
  }
  /**
   * Creates a TokenQueue suitable for this class's parse methods.
   * @param pos the position of the first character on in.
   * @param in closed by this method.
   * @param asXml true to parse as XML, false as HTML.
   * @throws IOException when in raises an exception during read.
   */
  public static TokenQueue<HtmlTokenType> makeTokenQueue(
      FilePosition pos, Reader in, boolean asXml) throws IOException {
    CharProducer cp = CharProducer.Factory.create(in, pos);
    HtmlLexer lexer = new HtmlLexer(cp);
    lexer.setTreatedAsXml(asXml);
    return new TokenQueue<HtmlTokenType>(lexer, pos.source());
  }

  /**
   * Parses a single top level construct, an element, or a text chunk from the
   * given queue.
   * @throws ParseException if elements are unbalanced -- sgml instead of xml
   *   attributes are missing values, or there is no top level construct to
   *   parse, or if there is a problem parsing the underlying stream.
   */
  private void parseDom(OpenElementStack out) throws ParseException {
    while (true) {
      Token<HtmlTokenType> t = tokens.pop();
      switch (t.type) {
        case TAGBEGIN:
          {
            List<Attr> attribs;
            Token<HtmlTokenType> end;
            if (isClose(t)) {
              attribs = Collections.<Attr>emptyList();
              while (true) {
                end = tokens.pop();
                if (end.type == HtmlTokenType.TAGEND) { break; }
                // If this is not a tagend, then we should require
                // ignorable whitespace when we're parsing strictly.
                if (end.type != HtmlTokenType.IGNORABLE) {
                  mq.addMessage(
                      DomParserMessageType.IGNORING_TOKEN,
                      end.pos, MessagePart.Factory.valueOf(end.text));
                }
              }
            } else {
              attribs = new ArrayList<Attr>();
              end = parseTagAttributes(out.getDocument(), t.pos, attribs);
            }
            attribs = Collections.unmodifiableList(attribs);
            try {
              out.processTag(t, end, attribs);
            } catch (IllegalDocumentStateException ex) {
              throw new ParseException(ex.getCajaMessage(), ex);
            }
          }
          return;
        case CDATA:
        case TEXT:
        case UNESCAPED:
          out.processText(t);
          return;
        case COMMENT:
          continue;
        default:
          throw new ParseException(new Message(
              MessageType.MALFORMED_XHTML, t.pos,
              MessagePart.Factory.valueOf(t.text)));
      }
    }
  }

  /**
   * Parses attributes onto children and consumes and returns the end of tag
   * token.
   */
  private Token<HtmlTokenType> parseTagAttributes(
      Document doc, FilePosition start, List<? super Attr> children)
      throws ParseException {
    Token<HtmlTokenType> last;
    tokloop:
    while (true) {
      last = tokens.peek();
      switch (last.type) {
      case TAGEND:
        tokens.advance();
        break tokloop;
      case ATTRNAME:
        Attr a = parseAttrib(doc);
        if (a != null) {
          children.add(a);
        }
        break;
      default:
        throw new ParseException(new Message(
            MessageType.MALFORMED_XHTML, FilePosition.span(start, last.pos),
            MessagePart.Factory.valueOf(last.text)));
      }
    }
    return last;
  }

  /**
   * Parses an element from a token stream.
   */
  private Attr parseAttrib(Document doc) throws ParseException {
    Token<HtmlTokenType> name = tokens.pop();
    Token<HtmlTokenType> value = tokens.peek();
    if (value.type == HtmlTokenType.ATTRVALUE) {
      tokens.advance();
      if (isAmbiguousAttributeValue(value.text)) {
        mq.addMessage(MessageType.AMBIGUOUS_ATTRIBUTE_VALUE,
                      FilePosition.span(name.pos, value.pos),
                      MessagePart.Factory.valueOf(name.text),
                      MessagePart.Factory.valueOf(value.text));
      }
    } else if (asXml) {
      // XML does not allow valueless attributes.
      throw new ParseException(
          new Message(MessageType.MISSING_ATTRIBUTE_VALUE,
                      value.pos, MessagePart.Factory.valueOf(value.text)));
    } else {
      value = Token.instance(name.text, HtmlTokenType.ATTRVALUE, name.pos);
    }
    Name attrName = (asXml ? Name.xml(name.text) : Name.html(name.text));
    Attr a;
    try {
      a = doc.createAttribute(attrName.getCanonicalForm());
    } catch (DOMException ex) {
      return null;
    }
    String rawValue = value.text;
    String decodedValue = null;
    int vlen = rawValue.length();
    if (vlen >= 2) {
      char ch0 = rawValue.charAt(0);
      char chn = rawValue.charAt(vlen - 1);
      int start = 0, end = vlen;
      if (chn == '"' || chn == '\'') {
        --end;
        // Handle unbalanced quotes as in <foo bar=baz">
        if (ch0 == chn) { start = 1; }
      }
      decodedValue = Nodes.decode(rawValue.substring(start, end));
    } else {
      decodedValue = Nodes.decode(rawValue);
    }
    a.setValue(decodedValue);
    if (needsDebugData) {
      Nodes.setFilePositionFor(a, name.pos);
      Nodes.setFilePositionForValue(a, value.pos);
      Nodes.setRawValue(a, rawValue);
    }
    return a;
  }

  /**
   * True iff the given tag is an end tag.
   * @param t a token with type {@link HtmlTokenType#TAGBEGIN}
   */
  private static boolean isClose(Token<HtmlTokenType> t) {
    return t.text.startsWith("</");
  }

  private static boolean guessAsXml(LookaheadLexer la, InputSource is)
      throws ParseException {
    Token<HtmlTokenType> first = la.peek();
    Token<HtmlTokenType> firstNonSpace = first;
    if (firstNonSpace != null && "".equals(firstNonSpace.text.trim())) {
      Token<HtmlTokenType> space = firstNonSpace;
      la.next();
      firstNonSpace = la.peek();
      la.pushBack(space);
    }
    if (firstNonSpace == null) {
      return false;  // An empty document is not valid XML.
    }
    switch (firstNonSpace.type) {
      case DIRECTIVE:
        return firstNonSpace.text.startsWith("<?xml")
            || (firstNonSpace.text.startsWith("<!DOCTYPE")
                && !isHtmlDoctype(firstNonSpace.text));
      case TAGBEGIN:  // A namespaced tag name.
        if (firstNonSpace.text.indexOf(':') >= 0) { return true; }
        break;
      default: break;
    }
    // If we have a file extension, and this XML starts the file, instead
    // of being parsed from a CDATA section inside a larger document, then
    // guess based on the file extension.
    if (FilePosition.startOf(first.pos).equals(
            FilePosition.startOfFile(first.pos.source()))) {
      String path = is.getUri().getPath();
      if (path != null) {
        String ext = Strings.toLowerCase(
            path.substring(path.lastIndexOf('.') + 1));
        if ("html".equals(ext)) { return false; }
        if ("xml".equals(ext) || "xhtml".equals(ext)) { return true; }
      }
    }
    return false;
  }

  private static boolean isHtmlDoctype(String s) {
    // http://htmlhelp.com/tools/validator/doctype.html
    return Pattern.compile("(?i:^<!DOCTYPE\\s+HTML\\b)").matcher(s).find()
        && !Pattern.compile("(?i:\\bXHTML\\b)").matcher(s).find();
  }

  private static final Pattern AMBIGUOUS_VALUE = Pattern.compile(
      "^\\w+\\s*=");
  /**
   * True for the attribute value 'bar=baz' in {@code <a foo= bar=baz>}
   * which a naive reader might interpret as {@code <a foo="" bar="baz">}.
   */
  private static boolean isAmbiguousAttributeValue(String attributeText) {
    return AMBIGUOUS_VALUE.matcher(attributeText).find();
  }
}


/**
 * A TokenStream that wraps another TokenStream to provide an arbitrary
 * amount of token lookahead.
 * Used to allow the parser to examine the first non-whitespace & non-comment
 * token to determine whether to parse subsequent tokens as HTML or XML.
 */
final class LookaheadLexer implements TokenStream<HtmlTokenType> {
  private final TokenStream<HtmlTokenType> lexer;
  private List<Token<HtmlTokenType>> pending
      = new LinkedList<Token<HtmlTokenType>>();

  LookaheadLexer(TokenStream<HtmlTokenType> lexer) {
    this.lexer = lexer;
  }

  /** True if {@link #next} is safe to call. */
  public boolean hasNext() throws ParseException {
    return !pending.isEmpty() || lexer.hasNext();
  }
  /** Returns the next token, and moves the stream position forward. */
  public Token<HtmlTokenType> next() throws ParseException {
    return pending.isEmpty() ? lexer.next() : pending.remove(0);
  }
  /** Returns the next token without consuming it, or null if no such token. */
  Token<HtmlTokenType> peek() throws ParseException {
    if (pending.isEmpty()) {
      if (!lexer.hasNext()) { return null; }
      Token<HtmlTokenType> next = lexer.next();
      pending.add(next);
      return next;
    }
    return pending.get(pending.size() - 1);
  }

  /**
   * Pushed t onto the head of the lookahead queue so that it becomes the
   * token returned by the next call to {@link #peek} or {@link #next}.
   */
  void pushBack(Token<HtmlTokenType> t) {
    pending.add(0, t);
  }
}
