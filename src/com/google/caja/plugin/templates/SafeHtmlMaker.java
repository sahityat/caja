// Copyright (C) 2009 Google Inc.
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

package com.google.caja.plugin.templates;

import com.google.caja.lexer.FilePosition;
import com.google.caja.parser.ParseTreeNode;
import com.google.caja.parser.html.Nodes;
import com.google.caja.parser.js.Block;
import com.google.caja.parser.js.Expression;
import com.google.caja.parser.js.Statement;
import com.google.caja.parser.js.StringLiteral;
import com.google.caja.parser.js.TranslatedCode;
import com.google.caja.plugin.ExtractedHtmlContent;
import com.google.caja.plugin.PluginMeta;
import com.google.caja.reporting.MessageContext;
import com.google.caja.util.Name;
import com.google.caja.util.Pair;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.DocumentFragment;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.Text;

/**
 * Produces safe static HTML from a DOM tree that has been compiled by the
 * {@link TemplateCompiler}.
 * This class emits two parse trees: safe HTML that is safe stand-alone, and
 * a number of blocks of Valija/Cajita which will add dynamic attributes to the
 * static HTML interspersed with extracted scripts.
 *
 * <h3>Glossary</h3>
 * <dl>
 *   <dt>Safe HTML</dt>
 *     <dd>HTML without event handlers or script, or names that can only be
 *     resolved on the browser.  This will include styles and classNames, and
 *     when {@link PluginMeta#getIdClass()} is specified, it will also include
 *     style-sheets and most IDs which is enough for a fully styled static view.
 *     </dd>
 *   <dt>Inline Script</dt>
 *     <dd>A script block as extracted by {@link ExtractedHtmlContent} that
 *     needs to run in the context of the portion of the static HTML that
 *     precedes it.  Scripts need to happen in the right context when they
 *     generate new content as in
 *     <xmp><ul><li>Item 1</li><script>emitItem2()</script><li>Item 3</ul></xmp>
 *     </dd>
 *   <dt>Skeleton</dt>
 *     <dd>A distilled version of the safe HTML that includes only elements,
 *     text, and document fragments.</dd>
 *   <dt>Bones</dt>
 *     <dd>References to the nodes in the skeleton in DF order with inline
 *     scripts.</dd>
 *   <dt>Static Attribute</dt>
 *     <dd>Attributes that can be rewritten server-side and included in the
 *     safe HTML.</dd>
 *   <dt>Dynamic Attribute</dt>
 *     <dd>Attributes that cannot be rewritten server-side or cannot be included
 *     in the safe HTML, and so which need to be attached by javascript.</dd>
 *   <dt>Auto-generated ID</dt>
 *     <dd>An auto-generated ID attached statically to a node in the Safe HTML
 *     so that javascript can find the node later and attach dynamic attributes.
 *   <dt>HTML Emitter</dt>
 *     <dd>A class that helps attach dynamic attributes and which provides
 *     an {@code attach} that is used to make sure that inline scripts only see
 *     the relevant bit of the DOM.</dd>
 * </dl>
 *
 * @author mikesamuel@gmail.com
 */
final class SafeHtmlMaker {
  private static final Name ID = Name.html("id");

  private final PluginMeta meta;
  private final MessageContext mc;
  private final Document doc;
  private final List<Block> js = new ArrayList<Block>();
  private final Map<Node, ParseTreeNode> scriptsPerNode;
  private Block currentBlock = null;
  /** True iff the current block is in a {@link TranslatedCode} section. */
  private boolean currentBlockStyle;
  /** True iff JS contains the definitions required by HtmlEmitter calls. */
  boolean started = false;
  /** True iff JS contains a HtmlEmitter.finish() call to release resources. */
  boolean finished = false;
  List<Node> roots;
  List<Statement> handlers;
  /**
   * @param doc the owner document for the safe HTML. Used only as a
   * factory for DOM nodes.
   */
  SafeHtmlMaker(PluginMeta meta, MessageContext mc, Document doc,
                Map<Node, ParseTreeNode> scriptsPerNode,
                List<Node> roots,
                List<Statement> handlers) {
    this.meta = meta;
    this.mc = mc;
    this.doc = doc;
    this.scriptsPerNode = scriptsPerNode;
    this.roots = roots;
    this.handlers = handlers;
  }

  Pair<Node, List<Block>> make() {
    js.clear();
    currentBlock = null;

    // Attach the event handlers to the DOM.
    for (Statement handlerDef : handlers) {
      emitStatement(handlerDef);
    }

    // Build the HTML and the javascript that adds dynamic attributes and that
    // executes inline scripts.

    // First we build a skeleton which maps a safe DOM to a list of "bones"
    // which include element start tags, text nodes, and embedded scripts in
    // depth-first order.
    List<DomBone> domSkeleton = new ArrayList<DomBone>();
    List<Node> safe = new ArrayList<Node>(roots.size());
    for (Node root : roots) {
      Node one = makeSkeleton(root, domSkeleton);
      if (one != null) { safe.add(one); }
    }

    fleshOutSkeleton(domSkeleton);

    return Pair.<Node, List<Block>>pair(
        consolidateHtml(safe), new ArrayList<Block>(js));
  }

  /** Part of a DOM skeleton. */
  private static class DomBone {}

  private static class NodeBone extends DomBone {
    final Node node;
    final Node safeNode;
    NodeBone(Node node, Node safeNode) {
      this.node = node;
      this.safeNode = safeNode;
    }
    @Override
    public String toString() {
      return "(" + getClass().getSimpleName()
          + " " + safeNode.getNodeName() + ")";
    }
  }

  private static class ScriptBone extends DomBone {
    final Block script;
    ScriptBone(Block script) {
      this.script = script;
    }
    @Override
    public String toString() {
      return "(" + getClass().getSimpleName() + ")";
    }
  }

  /**
   * Produces a skeletal static HTML tree containing only Elements, Text nodes
   * and DocumentFragments, and a set of "bones" including the elements and
   * extracted script elements in depth-first order.
   *
   * <xmp> <ul> <li>Hello <script>foo()</script> Bar</li> </ul> </xmp>
   * results in
   * {@code ((Node UL) (Node LI) (Text "Hello ") (Script foo()) (Text " Bar"))}.
   */
  private Node makeSkeleton(Node n, List<DomBone> bones) {
    if (!scriptsPerNode.containsKey(n)) { return null; }
    Node safe;
    switch (n.getNodeType()) {
      case Node.ELEMENT_NODE:
        Element el = (Element) n;
        Block script = ExtractedHtmlContent.getExtractedScriptFor(el);
        if (script != null) {
          bones.add(new ScriptBone(script));
          return null;
        } else {
          FilePosition pos = Nodes.getFilePositionFor(el);
          safe = doc.createElement(el.getTagName());
          Nodes.setFilePositionFor(safe, pos);
          bones.add(new NodeBone(n, safe));

          for (Node child : Nodes.childrenOf(el)) {
            Node safeChild = makeSkeleton(child, bones);
            if (safeChild != null) { safe.appendChild(safeChild); }
          }
        }
        break;
      case Node.TEXT_NODE:
        safe = doc.createTextNode(n.getNodeValue());
        Nodes.setFilePositionFor(safe, Nodes.getFilePositionFor(n));
        bones.add(new NodeBone(n, safe));
        break;
      case Node.DOCUMENT_FRAGMENT_NODE:
        safe = doc.createDocumentFragment();
        for (Node child : Nodes.childrenOf(n)) {
          Node safeChild = makeSkeleton(child, bones);
          if (safeChild != null) { safe.appendChild(safeChild); }
        }
        break;
      default: return null;
    }
    return safe;
  }

  /**
   * Walks the {@link #makeSkeleton skeleton}, adds static attributes, and
   * auto-generated IDs to the skeleton, and generates Javascript that adds
   * dynamic attributes to the static HTML and that executes inline scripts.
   */
  private void fleshOutSkeleton(List<DomBone> bones) {
    int n = bones.size();
    // The index of the first script bone not followed by any non-script bones.
    int firstDeferredScriptIndex = n;
    while (firstDeferredScriptIndex > 0
           && bones.get(firstDeferredScriptIndex - 1) instanceof ScriptBone) {
      --firstDeferredScriptIndex;
    }
    for (int i = 0; i < n; ++i) {
      DomBone bone = bones.get(i);
      if (bone instanceof ScriptBone) {
        fleshOutScriptBlock(((ScriptBone) bone).script);
      } else {
        NodeBone nb = (NodeBone) bone;
        boolean splitDom = i + 1 < n && bones.get(i + 1) instanceof ScriptBone;
        if (splitDom && i + 1 == firstDeferredScriptIndex) {
          splitDom = false;
          finish();
        }
        if (splitDom) { start(); }
        if (nb.node instanceof Text) {
          fleshOutText((Text) nb.safeNode, splitDom);
        } else {
          fleshOutElement((Element) nb.node, (Element) nb.safeNode, splitDom);
        }
      }
    }
    finish();
    signalLoaded();
  }

  /** Define bits needed by the emitter calls and the attribute fixup. */
  private void start() {
    if (!started) {
      emitStatement(quasiStmt("var el___;"));
      emitStatement(quasiStmt("var emitter___ = IMPORTS___.htmlEmitter___;"));
      started = true;
    }
  }

  /** Release resources held by the emitter. */
  private void finish() {
    if (started && !finished) {
      emitStatement(quasiStmt("el___ = emitter___./*@synthetic*/finish();"));
      finished = true;
    }
  }

  /** Call the document's "onload" listeners. */
  private void signalLoaded() {
    if (started) {
      emitStatement(quasiStmt("emitter___./*@synthetic*/signalLoaded();"));
    }
  }

  /** Emit an inlined script. */
  private void fleshOutScriptBlock(Block script) {
    FilePosition unk = FilePosition.UNKNOWN;

    String sourcePath = mc.abbreviate(script.getFilePosition().source());
    finishBlock();
    emitStatement(quasiStmt(
        ""
        + "try {"
        + "  @scriptBody;"
        + "} catch (ex___) {"
        + "  ___./*@synthetic*/ getNewModuleHandler()"
        // getNewModuleHandler is appropriate here since there can't be multiple
        // module handlers in play while loadModule is being called, and all
        // these exception handlers are only reachable while control is in
        // loadModule.
        + "      ./*@synthetic*/ handleUncaughtException("
        + "      ex___, onerror, @sourceFile, @line);"
        + "}",
        // TODO(ihab.awad): Will add UncajoledModule wrapper when we no longer
        // "consolidate" all scripts in an HTML file into one Caja module.
        "scriptBody", /*new UncajoledModule*/(script),
        "sourceFile", StringLiteral.valueOf(unk, sourcePath),
        "line", StringLiteral.valueOf(
            unk, String.valueOf(script.getFilePosition().startLineNo()))
        ), false);
  }

  /**
   * Emit a text block.
   * @param safe a text block that is safe, e.g. not in a context where it would
   *     cause code to execute.
   * @param splitDom true if this text node is immediately followed by an inline
   *     script block.
   */
  private void fleshOutText(Text safe, boolean splitDom) {
    if (splitDom) {
      String dynId = meta.generateUniqueName(ID.getCanonicalForm());
      emitStatement(quasiStmt(
          ""
          + "emitter___./*@synthetic*/unwrap("
          + "    emitter___./*@synthetic*/attach(@id));",
          "id", StringLiteral.valueOf(FilePosition.UNKNOWN, dynId)));

      Element wrapper = doc.createElement("span");
      wrapper.setAttribute(ID.getCanonicalForm(), dynId);
      Nodes.setFilePositionFor(wrapper, Nodes.getFilePositionFor(safe));
      safe.getParentNode().replaceChild(wrapper, safe);
      wrapper.appendChild(safe);
    }
  }

  /**
   * Attaches attributes to the safe DOM node corresponding to those on el.
   *
   * @param el an element in the input DOM.
   * @param safe the element in the safe HTML Dom corresponding to el.
   * @param splitDom true if this text node is immediately followed by an inline
   *     script block.
   */
  private void fleshOutElement(Element el, Element safe, boolean splitDom) {
    FilePosition pos = Nodes.getFilePositionFor(el);

    // An ID we attach to a node so that we can retrieve it to add dynamic
    // attributes later.
    String dynId = null;
    if (splitDom) {
      dynId = makeDynamicId(null, pos);
      // Emit first since this makes sure the node is in the DOM.
      emitStatement(quasiStmt(
          "emitter___./*@synthetic*/attach(@id);",
          "id", StringLiteral.valueOf(FilePosition.UNKNOWN, dynId)));
    }
    Nodes.setFilePositionFor(safe, pos);

    Attr id = null;
    for (Attr a : Nodes.attributesOf(el)) {
      if (!scriptsPerNode.containsKey(a)) { continue; }
      Name attrName = Name.html(a.getName());
      // Keep track of whether there is an ID so that we know whether or
      // not to remove any auto-generated ID later.
      Expression dynamicValue = (Expression) scriptsPerNode.get(a);
      if (!ID.equals(attrName)) {
        if (dynamicValue == null
            || dynamicValue instanceof StringLiteral) {
          emitStaticAttr(a, (StringLiteral) dynamicValue, safe);
        } else {
          dynId = makeDynamicId(dynId, pos);
          emitDynamicAttr(a, dynamicValue);
        }
      } else {
        // A previous HTML parsing step should have ensured that each element
        // only has one instance of each attribute.
        assert id == null;
        id = a;
      }
    }
    // Output an ID
    if (id != null) {
      Expression dynamicValue = (Expression) scriptsPerNode.get(id);
      if (dynId == null
          && (dynamicValue == null
              || dynamicValue instanceof StringLiteral)) {
        emitStaticAttr(id, (StringLiteral) dynamicValue, safe);
      } else {
        dynId = makeDynamicId(dynId, pos);
        emitDynamicAttr(id, dynamicValue);
      }
    }

    // Remove the dynamic ID if it is still present.
    if (dynId != null) {
      assert !safe.hasAttribute(ID.getCanonicalForm());
      safe.setAttribute(ID.getCanonicalForm(), dynId);
      if (id == null) {
        emitStatement(quasiStmt(
            "el___./*@synthetic*/removeAttribute('id');"));
      }
    }
  }

  private void emitStaticAttr(
      Attr a, StringLiteral dynamicValue, Element safe) {
    // Emit an attribute with a known value in the safe HTML.
    Attr safeAttr = doc.createAttribute(a.getName());
    safeAttr.setValue(
        dynamicValue == null
        ? a.getValue()
        : dynamicValue.getUnquotedValue());
    Nodes.setFilePositionFor(safeAttr, Nodes.getFilePositionFor(a));
    Nodes.setFilePositionForValue(
        safeAttr, Nodes.getFilePositionForValue(a));
    safe.setAttributeNode(safeAttr);
  }

  private void emitDynamicAttr(Attr a, Expression dynamicValue) {
    // Emit a statement to attach the dynamic attribute.
    emitStatement(quasiStmt(
        "emitter___./*@synthetic*/setAttr(el___, @name, @value);",
        "name", StringLiteral.valueOf(
            Nodes.getFilePositionFor(a), a.getName()),
        "value", dynamicValue));
    // TODO(mikesamuel): do we need to emit a static attribute when the
    // default value does not match the value criterion?
  }

  private String makeDynamicId(String dynId, FilePosition pos) {
    start();
    if (dynId == null) {
      // We need a dynamic ID so that we can find the node so that
      // we can attach dynamic attributes.
      dynId = meta.generateUniqueName(ID.getCanonicalForm());
      emitStatement(quasiStmt(
          "el___ = emitter___./*@synthetic*/byId(@id);",
          "id", StringLiteral.valueOf(pos, dynId)));
    }
    assert isDynamicId(dynId);
    return dynId;
  }

  private static final Pattern DYNID_PATTERN = Pattern.compile("id_\\d+___");
  private static boolean isDynamicId(String id) {
    return DYNID_PATTERN.matcher(id).matches();
  }

  private static Statement quasiStmt(String quasi, Object... args) {
    return QuasiUtil.quasiStmt(quasi, args);
  }

  private void emitStatement(Statement s) { emitStatement(s, true); }

  private void emitStatement(Statement s, boolean translated) {
    if (translated != currentBlockStyle) {
      currentBlock = null;
    }
    if (currentBlock == null) {
      Block block = new Block();
      js.add(block);
      if (translated) {
        TranslatedCode code = new TranslatedCode(currentBlock = new Block());
        block.appendChild(code);
      } else {
        currentBlock = block;
      }
      currentBlockStyle = translated;
    }
    currentBlock.appendChild(s);
  }

  private Node consolidateHtml(List<Node> nodes) {
    if (nodes.isEmpty()) { return doc.createDocumentFragment(); }
    Node first = nodes.get(0);
    List<Node> rest = nodes.subList(1, nodes.size());
    if (rest.isEmpty()) { return first; }
    FilePosition pos = Nodes.getFilePositionFor(first);
    DocumentFragment f;
    if (first instanceof DocumentFragment) {
      f = (DocumentFragment) first;
    } else {
      f = doc.createDocumentFragment();
      f.appendChild(first);
    }
    for (Node one : rest) {
      pos = FilePosition.span(pos, Nodes.getFilePositionFor(one));
      if (one instanceof DocumentFragment) {
        for (Node c = one.getFirstChild(), next; c != null; c = next) {
          next = c.getNextSibling();
          f.appendChild(c);
        }
      } else {
        f.appendChild(one);
      }
    }
    Nodes.setFilePositionFor(f, pos);
    return f;
  }

  private void finishBlock() { currentBlock = null; }
}
