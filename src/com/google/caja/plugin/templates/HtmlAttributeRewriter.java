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

import com.google.caja.lang.css.CssSchema;
import com.google.caja.lang.html.HTML;
import com.google.caja.lang.html.HtmlSchema;
import com.google.caja.lexer.CharProducer;
import com.google.caja.lexer.CssTokenType;
import com.google.caja.lexer.ExternalReference;
import com.google.caja.lexer.FilePosition;
import com.google.caja.lexer.JsLexer;
import com.google.caja.lexer.JsTokenQueue;
import com.google.caja.lexer.Keyword;
import com.google.caja.lexer.ParseException;
import com.google.caja.lexer.TokenQueue;
import com.google.caja.parser.AncestorChain;
import com.google.caja.parser.ParseTreeNode;
import com.google.caja.parser.ParseTreeNodeContainer;
import com.google.caja.parser.Visitor;
import com.google.caja.parser.css.CssParser;
import com.google.caja.parser.css.CssTree;
import com.google.caja.parser.html.Nodes;
import com.google.caja.parser.js.Block;
import com.google.caja.parser.js.Declaration;
import com.google.caja.parser.js.Expression;
import com.google.caja.parser.js.FunctionConstructor;
import com.google.caja.parser.js.Identifier;
import com.google.caja.parser.js.Parser;
import com.google.caja.parser.js.Reference;
import com.google.caja.parser.js.Statement;
import com.google.caja.parser.js.StringLiteral;
import com.google.caja.parser.js.SyntheticNodes;
import com.google.caja.parser.quasiliteral.QuasiBuilder;
import com.google.caja.parser.quasiliteral.ReservedNames;
import com.google.caja.plugin.CssRewriter;
import com.google.caja.plugin.CssValidator;
import com.google.caja.plugin.PluginMeta;
import com.google.caja.reporting.MessageLevel;
import com.google.caja.reporting.MessagePart;
import com.google.caja.reporting.MessageQueue;
import com.google.caja.reporting.RenderContext;
import com.google.caja.util.Join;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.w3c.dom.Attr;

/**
 * Converts attribute values to expressions that produce safe values.
 *
 * @author mikesamuel@gmail.com
 */
public final class HtmlAttributeRewriter {
  private final PluginMeta meta;
  private final CssSchema cssSchema;
  private final HtmlSchema htmlSchema;
  private final MessageQueue mq;
  /** Maps handler attribute source to handler names. */
  private final Map<String, String> handlerCache
      = new HashMap<String, String>();
  /** Extracted event handler functions. */
  private final List<Statement> handlers = new ArrayList<Statement>();

  public HtmlAttributeRewriter(
      PluginMeta meta, CssSchema cssSchema, HtmlSchema htmlSchema,
      MessageQueue mq) {
    this.meta = meta;
    this.cssSchema = cssSchema;
    this.htmlSchema = htmlSchema;
    this.mq = mq;
  }

  public PluginMeta getPluginMeta() { return meta; }
  public CssSchema getCssSchema() { return cssSchema; }
  public HtmlSchema getHtmlSchema() { return htmlSchema; }
  public List<Statement> getHandlers() {
    return Collections.unmodifiableList(handlers);
  }

  public static abstract class AttrValue {
    final FilePosition valuePos;
    final HTML.Attribute attrInfo;
    abstract Expression getValueExpr();
    abstract String getPlainValue();
    abstract String getRawValue();

    AttrValue(FilePosition valuePos, HTML.Attribute attr) {
      this.valuePos = valuePos;
      this.attrInfo = attr;
    }
  }

  public static AttrValue fromAttr(final Attr a, HTML.Attribute attr) {
    return new AttrValue(Nodes.getFilePositionForValue(a), attr) {
      @Override
      Expression getValueExpr() {
        return StringLiteral.valueOf(valuePos, getPlainValue());
      }
      @Override
      String getPlainValue() { return a.getValue(); }
      @Override
      String getRawValue() { return Nodes.getRawValue(a); }
    };
  }

  public static final class SanitizedAttr {
    public final boolean isSafe;
    public final Expression result;
    SanitizedAttr(boolean isSafe, Expression result) {
      this.isSafe = isSafe;
      this.result = result;
    }
  }

  SanitizedAttr sanitizeStringValue(AttrValue attr) {
    Expression dynamicValue;
    FilePosition pos = attr.valuePos;
    String value = attr.getPlainValue();
    switch (attr.attrInfo.getType()) {
      case CLASSES:
        if (!checkRestrictedNames(value, pos)) { return noResult(attr); }
        String classes = Join.join(" ", identifiers(value));
        dynamicValue = null;
        if (!classes.equals(value)) {
          dynamicValue = StringLiteral.valueOf(pos, classes);
        }
        break;
      case FRAME_TARGET:
      case LOCAL_NAME:
        if (!checkRestrictedName(value, pos)) { return noResult(attr); }
        dynamicValue = null;
        break;
      case GLOBAL_NAME:
      case ID:
      case IDREF:
        if (!checkRestrictedName(value, pos)) { return noResult(attr); }
        dynamicValue = rewriteIdentifiers(pos, value);
        break;
      case IDREFS:
        if (!checkRestrictedNames(value, pos)) { return noResult(attr); }
        dynamicValue = rewriteIdentifiers(pos, value);
        break;
      case NONE:
        if (!attr.attrInfo.getValueCriterion().accept(value)) {
          return noResult(attr);
        }
        dynamicValue = null;
        break;
      case SCRIPT:
        String handlerFnName = handlerCache.get(value);
        if (handlerFnName == null) {
          Block b;
          try {
            b = parseJsFromAttrValue(attr);
          } catch (ParseException ex) {
            ex.toMessageQueue(mq);
            return noResult(attr);
          }
          if (b.children().isEmpty()) { return noResult(attr); }
          rewriteEventHandlerReferences(b);

          handlerFnName = meta.generateUniqueName("c");
          Declaration handler = (Declaration) QuasiBuilder.substV(
              ""
              + "var @handlerName = ___./*@synthetic*/markFuncFreeze("
              + "    /*@synthetic*/function ("
              + "        event, " + ReservedNames.THIS_NODE + ") { @body*; });",
              "handlerName", SyntheticNodes.s(
                  new Identifier(FilePosition.UNKNOWN, handlerFnName)),
              "body", new ParseTreeNodeContainer(b.children()));
          handlers.add(handler);
          handlerCache.put(value, handlerFnName);
        }

        FunctionConstructor eventAdapter
            = (FunctionConstructor) QuasiBuilder.substV(
            ""
            + "(/*@synthetic*/ function (event) {"
            + "  return /*@synthetic*/ (plugin_dispatchEvent___("
            + "      /*@synthetic*/this, event, "
            + "      ___./*@synthetic*/getId(IMPORTS___), @tail));"
            + "})",
            "tail", new Reference(SyntheticNodes.s(
                new Identifier(pos, handlerFnName))));
        eventAdapter.setFilePosition(pos);
        dynamicValue = eventAdapter;
        break;
      case STYLE:
        CssTree.DeclarationGroup decls;
        try {
          decls = parseStyleAttrib(attr);
          if (decls == null) { return noResult(attr); }
        } catch (ParseException ex) {
          ex.toMessageQueue(mq);
          return noResult(attr);
        }

        // The validator will check that property values are well-formed,
        // marking those that aren't, and identifies all URLs.
        CssValidator v = new CssValidator(cssSchema, htmlSchema, mq)
            .withInvalidNodeMessageLevel(MessageLevel.WARNING);
        v.validateCss(AncestorChain.instance(decls));
        // The rewriter will remove any unsafe constructs.
        // and put URLs in the proper filename namespace
        new CssRewriter(meta.getPluginEnvironment(), mq)
            .withInvalidNodeMessageLevel(MessageLevel.WARNING)
            .rewrite(AncestorChain.instance(decls));

        StringBuilder css = new StringBuilder();
        RenderContext rc = new RenderContext(decls.makeRenderer(css, null));
        decls.render(rc);
        rc.getOut().noMoreTokens();

        dynamicValue = StringLiteral.valueOf(pos, css);
        break;
      case URI:
        try {
          URI uri = new URI(value);
          ExternalReference ref = new ExternalReference(uri, pos);
          String rewrittenUri = meta.getPluginEnvironment()
              .rewriteUri(ref, attr.attrInfo.getMimeTypes());
          if (rewrittenUri == null) {
            mq.addMessage(
                IhtmlMessageType.MALFORMED_URI, pos,
                MessagePart.Factory.valueOf(uri.toString()));
            return noResult(attr);
          }
          dynamicValue = StringLiteral.valueOf(
              ref.getReferencePosition(), rewrittenUri);
        } catch (URISyntaxException ex) {
          mq.addMessage(
              IhtmlMessageType.MALFORMED_URI, pos,
              MessagePart.Factory.valueOf(value));
          return noResult(attr);
        }
        break;
      default:
        throw new RuntimeException(attr.attrInfo.getType().name());
    }
    return new SanitizedAttr(true, dynamicValue);
  }

  private static final Pattern ALLOWED_NAME = Pattern.compile(
      "^[\\p{Alpha}_:][\\p{Alnum}.\\-_:]*$");
  /** True if value is a valid XML names outside the restricted namespace. */
  boolean checkRestrictedName(String value, FilePosition pos) {
    if (ALLOWED_NAME.matcher(value).find()) { return true; }
    if (!"".equals(value)) {
      mq.addMessage(
          IhtmlMessageType.ILLEGAL_NAME, pos,
          MessagePart.Factory.valueOf(value));
    }
    return false;
  }

  /**
  * True iff value is a space separated group of XML names outside the
  * restricted namespace.
  */
  boolean checkRestrictedNames(String value, FilePosition pos) {
    if ("".equals(value)) { return true; }
    boolean ok = true;
    for (String ident : identifiers(value)) {
      if ("".equals(ident)) { continue; }
      if (!ALLOWED_NAME.matcher(ident).matches()) {
        mq.addMessage(
            IhtmlMessageType.ILLEGAL_NAME, pos,
            MessagePart.Factory.valueOf(ident));
        ok = false;
      }
    }
    return ok;
  }

/** "foo bar baz" -> "foo-suffix___ bar-suffix___ baz-suffix___". */
  private Expression rewriteIdentifiers(FilePosition pos, String names) {
    if ("".equals(names)) { return null; }
    String idClass = meta.getIdClass();
    if (idClass != null) {
      StringBuilder result = new StringBuilder(names.length());
      for (String ident : identifiers(names)) {
        if ("".equals(ident)) { continue; }
        if (result.length() != 0) { result.append(' '); }
        result.append(ident).append('-').append(idClass);
      }
      return StringLiteral.valueOf(pos, result.toString());
    } else {
      JsConcatenator concat = new JsConcatenator();
      boolean first = true;
      for (String ident : identifiers(names)) {
        if ("".equals(ident)) { continue; }
        concat.append(pos, (first ? "" : " ") + ident + "-");
        concat.append(
            (Expression) QuasiBuilder.substV("IMPORTS___.getIdClass___()"));
        first = false;
      }
      return concat.toExpression(false);
    }
  }

  /**
   * Convert "this" -> "thisNode___" in event handlers.  Event handlers are
   * run in a context where this points to the current node.
   * We need to emulate that but still allow the event handlers to be simple
   * functions, so we pass in the tamed node as the first parameter.
   *
   * The event handler goes from:<br>
   *   {@code if (this.type === 'text') alert(this.value); }
   * to a function like:<pre>
   *   function (thisNode___, event) {
   *     if (thisNode___.type === 'text') {
   *       alert(thisNode___.value);
   *     }
   *   }</pre>
   * <p>
   * And the resulting function is called via a handler attribute like
   * {@code onchange="plugin_dispatchEvent___(this, node, 1234, 'handlerName')"}
   */
  private static void rewriteEventHandlerReferences(Block block) {
    block.acceptPreOrder(
        new Visitor() {
          public boolean visit(AncestorChain<?> ancestors) {
            ParseTreeNode node = ancestors.node;
            // Do not recurse into closures.
            if (node instanceof FunctionConstructor) { return false; }
            if (node instanceof Reference) {
              Reference r = (Reference) node;
              if (Keyword.THIS.toString().equals(r.getIdentifierName())) {
                Identifier oldRef = r.getIdentifier();
                Identifier thisNode = new Identifier(
                    oldRef.getFilePosition(), ReservedNames.THIS_NODE);
                r.replaceChild(SyntheticNodes.s(thisNode), oldRef);
              }
              return false;
            }
            return true;
          }
        }, null);
  }

  /**
   * Parses an {@code onclick} handler's or other handler's attribute value
   * as a javascript statement.
   */
  private Block parseJsFromAttrValue(AttrValue attr) throws ParseException {
    FilePosition pos = attr.valuePos;
    CharProducer cp = fromAttrValue(attr);
    JsTokenQueue tq = new JsTokenQueue(new JsLexer(cp, false), pos.source());
    tq.setInputRange(pos);
    if (tq.isEmpty()) {
      return new Block(pos, Collections.<Statement>emptyList());
    }
    // Parse as a javascript block.
    Block b = new Parser(tq, mq).parse();
    // Block will be sanitized in a later pass.
    b.setFilePosition(pos);
    return b;
  }

  /**
   * Parses a style attribute's value as a CSS declaration group.
   */
  private CssTree.DeclarationGroup parseStyleAttrib(AttrValue attr)
      throws ParseException {
    return parseCssDeclarationGroup(fromAttrValue(attr), attr.valuePos);
  }

  CssTree.DeclarationGroup parseCssDeclarationGroup(
      CharProducer cp, FilePosition inputRange)
      throws ParseException {
    // Parse the CSS as a set of declarations separated by semicolons.
    TokenQueue<CssTokenType> tq = CssParser.makeTokenQueue(cp, mq, false);
    if (tq.isEmpty()) { return null; }
    if (inputRange != null) { tq.setInputRange(inputRange); }
    CssParser p = new CssParser(tq, mq, MessageLevel.WARNING);
    CssTree.DeclarationGroup decls = p.parseDeclarationGroup();
    tq.expectEmpty();
    return decls;
  }

  private static CharProducer fromAttrValue(AttrValue a) {
    String value = a.getPlainValue();
    FilePosition pos = a.valuePos;
    String rawValue = a.getRawValue();
    // Use the raw value so that the file positions come out right in
    // error messages.
    if (rawValue != null) {
      // The raw value is HTML so we wrap it in an HTML decoder.
      CharProducer cp = CharProducer.Factory.fromHtmlAttribute(
          CharProducer.Factory.fromString(deQuote(rawValue), pos));
      // Check if the attribute value has been set since parsing.
      if (String.valueOf(cp.getBuffer(), cp.getOffset(), cp.getLength())
          .equals(value)) {
        return cp;
      }
    }
    // Reached if no raw value stored or if the raw value is out of sync.
    return CharProducer.Factory.fromString(value, pos);
  }

  /** Strip quotes from an attribute value if there are any. */
  private static String deQuote(String s) {
    int len = s.length();
    if (len < 2) { return s; }
    char ch0 = s.charAt(0);
    return (('"' == ch0 || '\'' == ch0) && ch0 == s.charAt(len - 1))
           ? " " + s.substring(1, len - 1) + " "
           : s;
  }

  static SanitizedAttr noResult(AttrValue a) {
    String safeValue = a.attrInfo.getSafeValue();
    String defaultValue = a.attrInfo.getDefaultValue();
    if (safeValue != null && defaultValue != null
        && !safeValue.equals(defaultValue)) {
      return new SanitizedAttr(
          true, StringLiteral.valueOf(a.valuePos, safeValue));
    }
    return new SanitizedAttr(false, null);
  }

  /**
   * Splits an attribute value specified as a space separated group of
   * identifiers.
   */
  private static Iterable<String> identifiers(String idents) {
    idents = idents.trim();
    return "".equals(idents)
        ? Collections.<String>emptyList()
        : Arrays.asList(idents.trim().split("\\s+"));
  }
}
