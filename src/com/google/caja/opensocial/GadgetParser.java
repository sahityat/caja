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

package com.google.caja.opensocial;

import com.google.caja.lexer.CharProducer;
import com.google.caja.lexer.FilePosition;
import com.google.caja.lexer.HtmlLexer;
import com.google.caja.lexer.HtmlTokenType;
import com.google.caja.lexer.InputSource;
import com.google.caja.lexer.ParseException;
import com.google.caja.lexer.TokenConsumer;
import com.google.caja.lexer.TokenQueue;
import com.google.caja.parser.html.DomParser;
import com.google.caja.parser.html.Nodes;
import com.google.caja.plugin.Dom;
import com.google.caja.reporting.MessageQueue;
import com.google.caja.reporting.RenderContext;
import com.google.caja.util.Callback;
import com.google.caja.util.Name;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.w3c.dom.Text;

/**
 * Safe XML parser for gadget specifications. Rejects invalid markup.
 *
 * @author ihab.awad@gmail.com (Ihab Awad)
 */
public class GadgetParser {

  /**
   * Parse an OpenSocial gadget specification and return the result as a
   * {@link GadgetSpec}.
   *
   * @param gadgetSpec a gadget specification.
   * @param src the source of gadgetSpec.
   * @param view the view to parse
   * @exception ParseException if gadgetSpec is malformed.
   * @exception GadgetRewriteException if gadgetSpec doesn't validate.
   */
  public GadgetSpec parse(
      CharProducer gadgetSpec, InputSource src, String view, MessageQueue mq)
      throws GadgetRewriteException, ParseException {
    HtmlLexer lexer = new HtmlLexer(gadgetSpec);
    lexer.setTreatedAsXml(true);
    Element el = new DomParser(
        new TokenQueue<HtmlTokenType>(lexer, src), true, mq).parseDocument();

    Document doc = el.getOwnerDocument();
    GadgetSpec spec = new GadgetSpec();
    readModulePrefs(doc, spec);
    readRequiredFeatures(doc, spec);
    readContent(doc, spec, view);

    return spec;
  }

  private void readModulePrefs(Document doc, GadgetSpec spec)
      throws GadgetRewriteException {
    Iterator<Element> els = getElementsByTagName(doc, Name.xml("ModulePrefs"))
        .iterator();
    Element modulePrefs = null;
    if (els.hasNext()) { modulePrefs = els.next(); }
    check(modulePrefs != null && !els.hasNext(),
          "Must have exactly one <ModulePrefs>");
    for (Attr attr : Nodes.attributesOf(modulePrefs)) {
      spec.getModulePrefs().put(attr.getNodeName(), attr.getNodeValue());
    }
  }

  private void readRequiredFeatures(Document doc, GadgetSpec spec)
      throws GadgetRewriteException {
    for (Element require : getElementsByTagName(doc, Name.xml("Require"))) {
      Attr feature = require.getAttributeNode("feature");
      check(feature != null,
            "<Require> must have a \"feature\" attribute");
      spec.getRequiredFeatures().add(feature.getNodeValue());
    }
  }

  private void readContent(Document doc, GadgetSpec spec, String view)
      throws GadgetRewriteException {
    for (final Element contentNode
         : getElementsByTagName(doc, Name.xml("Content"))) {
      Attr viewAttr = contentNode.getAttributeNode("view");
      if (viewAttr == null
          || Arrays.asList(viewAttr.getNodeValue().trim().split("\\s*,\\s*"))
             .contains(view)) {
        Attr typeAttr = contentNode.getAttributeNode("type");
        check(typeAttr != null, "No 'type' attribute for view '" + view + "'");
        String value = typeAttr.getNodeValue();

        check(value.equals("html"), "Can't handle Content type '" + value +"'");

        spec.setContentType(value);

        spec.setContent(
            new GadgetSpec.CharProducerFactory() {
              public CharProducer producer() {
                List<CharProducer> chunks = new ArrayList<CharProducer>();
                for (Node child : Nodes.childrenOf(contentNode)) {
                  if (child.getNodeType() == Node.TEXT_NODE) {
                    Text t = (Text) child;
                    FilePosition tpos = Nodes.getFilePositionFor(t);
                    String rawText = Nodes.getRawText(t);
                    String plainText = t.getNodeValue();
                    CharProducer cp = null;
                    if (rawText != null) {
                      cp = CharProducer.Factory.fromHtmlAttribute(
                          CharProducer.Factory.create(
                              new StringReader(rawText), tpos));
                      if (!String.valueOf(
                              cp.getBuffer(), cp.getOffset(), cp.getLength())
                          .equals(plainText)) {
                        cp = null;
                      }
                    }
                    if (cp == null) {
                      cp = CharProducer.Factory.create(
                          new StringReader(plainText), tpos);
                    }
                    chunks.add(cp);
                  } else if (child.getNodeType() == Node.CDATA_SECTION_NODE) {
                    String cdata = child.getNodeValue();
                    FilePosition pos = Nodes.getFilePositionFor(child);
                    // reduce the position to exclude the <![CDATA[ and ]]>
                    pos = FilePosition.instance(
                        pos.source(),
                        pos.startLineNo(),
                        pos.startCharInFile() + 9, pos.startCharInLine() + 9,
                        pos.length() - 12);
                    chunks.add(CharProducer.Factory.create(
                        new StringReader(cdata), pos));
                  }
                }
                return CharProducer.Factory.chain(
                    chunks.toArray(new CharProducer[0]));
              }
            });
        return;
      }
    }

    throw new GadgetRewriteException("No content for view '" + view + "'");
  }

  /**
   * Render the given gadgetSpec as XML.
   *
   * @param output to which XML is written.
   */
  public void render(GadgetSpec gadgetSpec, Appendable output)
      throws IOException {
    try {
      Element doc = toDocument(gadgetSpec);
      Dom dom = new Dom(doc);
      TokenConsumer tc = dom.makeRenderer(output, new Callback<IOException>() {
        public void handle(IOException e) {
          throw new RenderFailure(e);
        }
      });
      dom.render(new RenderContext(tc).withAsXml(true));
      tc.noMoreTokens();
    } catch (RenderFailure e) {
      throw (IOException) e.getCause();
    }
  }
  private static class RenderFailure extends RuntimeException {
    RenderFailure(IOException ex) { initCause(ex); }
  }

  private Element toDocument(GadgetSpec gadgetSpec) throws IOException {
    Document doc = DomParser.makeDocument(null, null);

    Element modulePrefs = doc.createElement("ModulePrefs");
    for (Map.Entry<String, String> e : gadgetSpec.getModulePrefs().entrySet()) {
      modulePrefs.setAttribute(e.getKey(), e.getValue());
    }

    for (String feature : gadgetSpec.getRequiredFeatures()) {
      Element featureEl = doc.createElement("Require");
      featureEl.setAttribute("feature", feature);
      modulePrefs.appendChild(featureEl);
    }

    Element content = doc.createElement("Content");
    content.setAttribute("type", gadgetSpec.getContentType());
    content.appendChild(doc.createCDATASection(drain(gadgetSpec.getContent())));

    Element module = doc.createElement("Module");
    module.appendChild(modulePrefs);
    module.appendChild(content);

    doc.appendChild(module);
    return module;
  }

  private void check(boolean condition, String msg)
      throws GadgetRewriteException {
    if (!condition) throw new GadgetRewriteException(msg);
  }

  private String drain(CharProducer cp) {
    return String.valueOf(
        cp.getBuffer(), cp.getOffset(), cp.getLimit() - cp.getOffset());
  }

  private static Iterable<Element> getElementsByTagName(Document d, Name name) {
    NodeList elements = d.getElementsByTagName(name.getCanonicalForm());
    return Nodes.nodeListIterable(elements, Element.class);
  }
}
