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

package com.google.caja.parser.css;

import com.google.caja.lexer.FilePosition;
import com.google.caja.lexer.Token;
import com.google.caja.lexer.TokenConsumer;
import com.google.caja.lexer.escaping.Escaping;
import com.google.caja.parser.AncestorChain;
import com.google.caja.parser.ParseTreeNode;
import com.google.caja.parser.ParseTreeNodes;
import com.google.caja.parser.Visitor;
import com.google.caja.render.Concatenator;
import com.google.caja.render.JsPrettyPrinter;
import com.google.caja.reporting.MessageContext;
import com.google.caja.reporting.MessagePart;
import com.google.caja.reporting.RenderContext;
import com.google.caja.util.Callback;
import com.google.caja.util.Name;
import com.google.caja.util.Pair;
import com.google.caja.util.SyntheticAttributeKey;
import com.google.caja.util.SyntheticAttributes;

import java.io.IOException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.ListIterator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A description of the values that can occur after a property.
 *
 * <p>See the value description language from
 * http://www.w3.org/TR/CSS21/about.html#property-defs
 *
 * @author mikesamuel@gmail.com
 */
public abstract class CssPropertySignature implements ParseTreeNode {
  private final List<CssPropertySignature> children;
  private CssPropertySignature parent, nextSibling, prevSibling;

  CssPropertySignature(List<? extends CssPropertySignature> children) {
    this.children = children.isEmpty()
                  ? Collections.<CssPropertySignature>emptyList()
                  : Collections.unmodifiableList(
                        new ArrayList<CssPropertySignature>(children));
    CssPropertySignature last = null;
    for (CssPropertySignature child : children) {
      child.parent = this;
      child.prevSibling = last;
      if (null != last) { last.nextSibling = child; }
      last = child;
    }
    assert !this.children.contains(null);
  }

  @Override
  public CssPropertySignature clone() {
    return ParseTreeNodes.newNodeInstance(
        getClass(), getFilePosition(), getValue(), children());
  }

  public final TokenConsumer makeRenderer(
      Appendable out, Callback<IOException> exHandler) {
    return new JsPrettyPrinter(new Concatenator(out, exHandler));
  }

  /** A signature that can be repeated zero or more times. */
  public static final class RepeatedSignature extends CssPropertySignature {
    public final int minCount, maxCount;
    private RepeatedSignature(
        CssPropertySignature sig, int minCount, int maxCount) {
      super(Collections.singletonList(sig));
      this.minCount = minCount;
      this.maxCount = maxCount;
    }

    public Pair<Integer, Integer> getValue() {
      return Pair.pair(Integer.valueOf(minCount), Integer.valueOf(maxCount));
    }

    public CssPropertySignature getRepeatedSignature() {
      return children().get(0);
    }

    public void render(RenderContext r) {
      TokenConsumer out = r.getOut();
      children().get(0).render(r);
      out.consume("{");
      out.consume(String.valueOf(minCount));
      if (minCount != maxCount) {
        out.consume(",");
        if (Integer.MAX_VALUE != maxCount) {
          out.consume(String.valueOf(maxCount));
        }
      }
      out.consume("}");
    }
  }

  /** A signature that matches one of its children. */
  public static class SetSignature extends CssPropertySignature {
    private SetSignature(List<CssPropertySignature> alternatives) {
      super(alternatives);
    }

    public Object getValue() { return null; }

    public void render(RenderContext r) {
      TokenConsumer out = r.getOut();
      out.consume("[");
      boolean first = true;
      for (CssPropertySignature sig : children()) {
        if (!first) {
          out.consume("|");
        } else {
          first = false;
        }
        sig.render(r);
      }
      out.consume("]");
    }
  }

  /** A signature that matches its children. */
  public static final class ExclusiveSetSignature
      extends SetSignature {
    private ExclusiveSetSignature(
        List<CssPropertySignature> alternatives) {
      super(alternatives);
    }

    @Override
    public void render(RenderContext r) {
      TokenConsumer out = r.getOut();
      out.consume("[");
      boolean first = true;
      for (CssPropertySignature sig : children()) {
        if (!first) {
          out.consume("||");
        } else {
          first = false;
        }
        sig.render(r);
      }
      out.consume("]");
    }
  }

  /** A signature that matches its children in order. */
  public static final class SeriesSignature extends CssPropertySignature {
    private SeriesSignature(List<CssPropertySignature> children) {
      super(children);
    }
    public Object getValue() { return null; }

    public void render(RenderContext r) {
      TokenConsumer out = r.getOut();
      out.consume("[");
      boolean first = true;
      for (CssPropertySignature sig : children()) {
        if (!first) {
          out.consume(" ");
        } else {
          first = false;
        }
        sig.render(r);
      }
      out.consume("]");
    }
  }

  /** A signature that matches a literal string. */
  public static final class LiteralSignature extends CssPropertySignature {
    public final String value;
    private LiteralSignature(String value) {
      super(Collections.<CssPropertySignature>emptyList());
      this.value = value;
    }

    public String getValue() { return value; }

    public void render(RenderContext r) {
      r.getOut().consume(value);
    }
  }

  /**
   * A signature that matches a quoted string.
   * This production does not occur in the CSS property signature scheme, but
   * is required because of IE progid extensions which use quotes around keyword
   * values.
   */
  public static final class QuotedLiteralSignature
      extends CssPropertySignature {
    public final String value;
    private QuotedLiteralSignature(String value) {
      super(Collections.<CssPropertySignature>emptyList());
      this.value = value;
    }

    public String getValue() { return value; }

    public void render(RenderContext r) {
      StringBuilder sb = new StringBuilder(value.length() + 16);
      sb.append('"');
      Escaping.escapeCssString(value, sb);
      sb.append('"');
      r.getOut().consume(sb.toString());
    }
  }

  /** A signature that defers to a CSS 2 property signature. */
  public static final class PropertyRefSignature extends CssPropertySignature {
    public final Name name;
    private PropertyRefSignature(Name name) {
      super(Collections.<CssPropertySignature>emptyList());
      this.name = name;
    }

    public Name getValue() { return name; }

    public Name getPropertyName() { return name; }

    public void render(RenderContext r) {
      r.getOut().consume("\'" + name + "\'");
    }
  }

  /** A signature that defers to a CSS 2 symbol. */
  public static final class SymbolSignature extends CssPropertySignature {
    public final Name symbolName;
    private SymbolSignature(Name symbolName) {
      super(Collections.<CssPropertySignature>emptyList());
      this.symbolName = symbolName;
    }
    public Name getValue() { return symbolName; }

    public void render(RenderContext r) {
      TokenConsumer out = r.getOut();
      out.consume("<");
      out.consume(symbolName.getCanonicalForm());
      out.consume(">");
    }
  }

  /** A signature that matches a function call. */
  public static final class CallSignature extends CssPropertySignature {
    private CallSignature(List<CssPropertySignature> children) {
      super(children);
    }
    public Object getValue() { return null; }

    public void render(RenderContext r) {
      TokenConsumer out = r.getOut();
      ListIterator<? extends CssPropertySignature> childIt =
          children().listIterator();
      childIt.next().render(r);
      out.consume("(");
      while (childIt.hasNext()) {
        out.consume(" ");
        childIt.next().render(r);
      }
      out.consume(")");
    }
  }

  /**
   * Matches an IE CSS Filter, as described at
   * http://msdn.microsoft.com/en-us/library/ms532847(VS.85).aspx
   */
  public static final class ProgIdSignature extends CssPropertySignature {
    private final Name name;
    /**
     * @param name A dotted CSS name like DXImageTransform.Microsoft.Alpha.
     * @param attrs name value pairs.
     */
    private ProgIdSignature(Name name, List<ProgIdAttrSignature> attrs) {
      super(attrs);
      this.name = name;
    }
    public Name getValue() { return name; }
    public Name getName() { return name; }

    public void render(RenderContext r) {
      TokenConsumer out = r.getOut();
      out.consume("progid");
      out.consume(":");
      out.consume(name.getCanonicalForm());
      out.consume("(");
      boolean comma = false;
      for (CssPropertySignature a : children()) {
        if (comma) { out.consume(","); }
        comma = true;
        a.render(r);
      }
      out.consume(")");
    }

    public ProgIdAttrSignature getProgIdAttr(Name attrName) {
      for (CssPropertySignature child : children()) {
        ProgIdAttrSignature attr = (ProgIdAttrSignature) child;
        if (attrName.equals(attr.getName())) { return attr; }
      }
      return null;
    }
  }

  public static final class ProgIdAttrSignature extends CssPropertySignature {
    private final Name name;
    private ProgIdAttrSignature(Name name, CssPropertySignature valueSig) {
      super(Collections.singletonList(valueSig));
      this.name = name;
    }
    public Name getValue() { return name; }
    public Name getName() { return name; }
    public CssPropertySignature getValueSig() { return children().get(0); }

    public void render(RenderContext r) {
      TokenConsumer out = r.getOut();
      out.consume(name.getCanonicalForm());
      out.consume("=");
      children().get(0).render(r);
    }
  }

  public ParseTreeNode getParent() { return this.parent; }

  public ParseTreeNode getNextSibling() { return this.nextSibling; }

  public ParseTreeNode getPrevSibling() { return this.prevSibling; }

  public FilePosition getFilePosition() {
    throw new UnsupportedOperationException();
  }

  public List<Token<?>> getComments() {
    return Collections.<Token<?>>emptyList();
  }

  private SyntheticAttributes attribs;
  public SyntheticAttributes getAttributes() {
    if (true) {
      throw new UnsupportedOperationException();
    }
    // may be mutable for debugging
    if (null == attribs) { attribs = new SyntheticAttributes(); }
    return attribs;
  }

  public List<? extends CssPropertySignature> children() { return children; }

  public final boolean acceptPreOrder(Visitor v, AncestorChain<?> ancestors) {
    ancestors = AncestorChain.instance(ancestors, this);
    if (!v.visit(ancestors)) { return false; }
    for (CssPropertySignature child : children) {
      child.acceptPreOrder(v, ancestors);
    }
    return true;
  }

  public final boolean acceptPostOrder(Visitor v, AncestorChain<?> ancestors) {
    ancestors = AncestorChain.instance(ancestors, this);
    for (CssPropertySignature child : children) {
      if (!child.acceptPostOrder(v, ancestors)) {
        return false;
      }
    }
    return v.visit(ancestors);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    RenderContext rc = new RenderContext(makeRenderer(sb, null));
    render(rc);
    rc.getOut().noMoreTokens();
    return sb.toString();
  }

  protected void formatSelf(MessageContext context, Appendable out)
      throws IOException {
    out.append(getClass().getSimpleName());
    Object value = getValue();
    if (null != value) {
      out.append(" : ");
      if (value instanceof MessagePart) {
        ((MessagePart) value).format(context, out);
      } else {
        out.append(value.toString());
      }
    }
    if (null != attribs && !context.relevantKeys.isEmpty()) {
      for (SyntheticAttributeKey<?> k : attribs.keySet()) {
        if (context.relevantKeys.contains(k)) {
          out.append(" ; ").append(k.getName()).append('=');
          Object attribValue = attribs.get(k);
          if (attribValue instanceof MessagePart) {
            ((MessagePart) attribValue).format(context, out);
          } else {
            out.append(String.valueOf(attribValue));
          }
        }
      }
    }
  }

  public void format(MessageContext context, Appendable out)
      throws IOException {
    formatTree(context, 0, out);
  }

  public void formatTree(MessageContext context, int depth, Appendable out)
      throws IOException {
    for (int d = depth; --d >= 0;) { out.append("  "); }
    formatSelf(context, out);
    for (CssPropertySignature child : children()) {
      out.append("\n");
      child.formatTree(context, depth + 1, out);
    }
  }

  public static final class Parser {
    private static Pattern[] TOKENS = {
      // whitespace
      Pattern.compile("^\\s+"),
      // a symbol, possibly with numeric bounds
      Pattern.compile("^(<[a-zA-Z][\\w\\-]*(?:\\:\\d+,\\d*)?>)"),
      // a property reference
      Pattern.compile("^('[a-zA-Z][\\w\\-]*')"),
      // a literal keyword
      Pattern.compile("^(-?[a-zA-Z][\\w\\-]*)"),
      // a quoted literal
      Pattern.compile("^(\"[^\"]*\")"),
      // a number
      Pattern.compile("^([0-9]+)\\b"),
      // multi-character punctuation
      Pattern.compile("^(\\|\\|)"),
      // other single character tokens
      Pattern.compile("^([\\(\\)=\\{\\}\\*\\+\\,\\/\\|\\[\\]\\?\\.:])"),
    };
    static ListIterator<String> tokenizeSignature(String sig) {
      List<String> toks = new ArrayList<String>();
      while (!"".equals(sig)) {
        boolean match = false;
        for (Pattern p : TOKENS) {
          Matcher m = p.matcher(sig);
          if (m.find()) {
            if (m.groupCount() > 0) { toks.add(m.group(1)); }
            sig = sig.substring(m.end(0));
            match = true;
            break;
          }
        }
        if (!match) { throw new IllegalArgumentException(sig); }
      }
      return toks.listIterator();
    }

    public static CssPropertySignature parseSignature(String sig) {
      ListIterator<String> toks = tokenizeSignature(sig);
      CssPropertySignature signature = parseSignature(toks);
      if (toks.hasNext()) {
        throw new IllegalArgumentException(unroll(toks));
      }
      return signature;
    }

    static CssPropertySignature parseSignature(ListIterator<String> toks) {
      CssPropertySignature child = parseSeries(toks);
      if (toks.hasNext()) {
        String s = toks.next();

        // || matches multiple of its elements but each only once.
        if ("||".equals(s) || "|".equals(s)) {
          List<CssPropertySignature> children =
              new ArrayList<CssPropertySignature>();
          children.add(child);
          do {
            children.add(parseSeries(toks));
            if (!toks.hasNext()) { break; }
            if (!s.equals(toks.next())) {
              toks.previous();
              break;
            }
          } while (true);

          if ("||".equals(s)) {
            child = new ExclusiveSetSignature(children);
            child = new RepeatedSignature(child, 1, children.size());
          } else {
            child = new SetSignature(children);
          }
        } else {
          toks.previous();
        }
      }
      return child;
    }

    static CssPropertySignature parseSeries(ListIterator<String> toks) {
      CssPropertySignature first = parseSignatureAtom(toks);
      if (!toks.hasNext()) { return first; }
      String s = toks.next();
      if ("]".equals(s) || "|".equals(s) || "||".equals(s) || ")".equals(s)) {
        toks.previous();
        return first;
      }

      List<CssPropertySignature> children =
        new ArrayList<CssPropertySignature>();
      children.add(first);
      toks.previous();
      do {
        children.add(parseSignatureAtom(toks));
        if (!toks.hasNext()) { break; }
        s = toks.next();
        toks.previous();
      } while (!("]".equals(s) || "|".equals(s) || "||".equals(s)
                 || ")".equals(s)));
      return new SeriesSignature(children);
    }

    static CssPropertySignature parseSignatureAtom(ListIterator<String> toks) {
      String s = toks.next();
      CssPropertySignature sig;
      if ("[".equals(s)) {
        sig = parseSignature(toks);
        if (!"]".equals(toks.next())) {
          throw new IllegalArgumentException(unroll(toks));
        }
      } else if (Name.css("progid").equals(Name.css(s))) {
        if (":".equals(toks.next())) {
          sig = parseProgId(toks);
        } else {
          toks.previous();
          sig = new LiteralSignature(s);
        }
      } else {
        char ch0 = s.charAt(0);
        if (Character.isLetter(ch0)) { // a literal identifier
          sig = new LiteralSignature(s);
        } else if (ch0 == '\'') {  // a property reference
          sig = new PropertyRefSignature(
              Name.css(s.substring(1, s.length() - 1)));
        } else if (ch0 == '"') {  // a quoted literal
          sig = new QuotedLiteralSignature(
              CssParser.unescape(s.substring(1, s.length() - 1), false));
        } else if (ch0 == '<') {  // a symbol
          sig = new SymbolSignature(Name.css(s.substring(1, s.length() - 1)));
        } else { // a literal number or punctuation mark
          sig = new LiteralSignature(s);
        }
      }
      return parsePostOp(parseBracketOp(sig, toks), toks);
    }

    static CssPropertySignature parsePostOp(
        CssPropertySignature sig, ListIterator<String> toks) {
      if (!toks.hasNext()) { return sig; }
      String s = toks.next();
      int min, max;
      if (s.equals("{")) {
        try {
          min = Integer.parseInt(toks.next());
          s = toks.next();
          if (",".equals(s)) {
            max = Integer.parseInt(toks.next());
          } else {
            max = min;
          }
        } catch (NumberFormatException ex) {
          throw new IllegalArgumentException(unroll(toks), ex);
        }
        if (!"}".equals(toks.next())) {
          throw new IllegalArgumentException(unroll(toks));
        }
      } else if (s.equals("*")) {
        min = 0;
        max = Integer.MAX_VALUE;
      } else if (s.equals("?")) {
        min = 0;
        max = 1;
      } else if (s.equals("+")) {
        min = 1;
        max = Integer.MAX_VALUE;
      } else {
        toks.previous();
        return sig;
      }
      if (sig instanceof RepeatedSignature) {
        RepeatedSignature rsig = (RepeatedSignature) sig;
        sig = rsig.children().get(0);
        min = Math.min(min, rsig.minCount);
        long lmax = max * rsig.maxCount;
        max = (lmax > Integer.MAX_VALUE) ? Integer.MAX_VALUE : (int) lmax;
      }
      return new RepeatedSignature(sig, min, max);
    }

    static CssPropertySignature parseBracketOp(
        CssPropertySignature sig, ListIterator<String> toks) {
      if (!toks.hasNext()) { return sig; }

      if ("(".equals(toks.next())) {
        List<CssPropertySignature> children =
          new ArrayList<CssPropertySignature>();
        children.add(sig);
        if (!")".equals(toks.next())) {
          toks.previous();
          children.add(parseSignature(toks));
          if (!")".equals(toks.next())) {
            throw new IllegalArgumentException(unroll(toks));
          }
        }
        return new CallSignature(children);
      } else {
        toks.previous();
        return sig;
      }
    }

    private static final Pattern DOTTED_NAME = Pattern.compile(
        "^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)*$", Pattern.CASE_INSENSITIVE);
    static ProgIdSignature parseProgId(ListIterator<String> toks) {
      StringBuilder name = new StringBuilder();
      while (true) {
        String t = toks.next();
        if ("(".equals(t)) { break; }
        name.append(t);
      }
      if (!DOTTED_NAME.matcher(name).matches()) {
        throw new IllegalArgumentException(
            "Not dotted name: " + name.toString());
      }
      List<ProgIdAttrSignature> attrs = new ArrayList<ProgIdAttrSignature>();
      String t = toks.next();
      if (!")".equals(t)) {
        while (true) {
          if (!Character.isLetter(t.charAt(0))) {
            throw new IllegalArgumentException("Not attr name: " + t);
          }
          Name attrName = Name.css(t);
          t = toks.next();
          if (!"=".equals(t)) {
            throw new IllegalArgumentException("Not '=':" + t);
          }
          CssPropertySignature valueSig = parseSignatureAtom(toks);
          attrs.add(new ProgIdAttrSignature(attrName, valueSig));
          t = toks.next();
          if (")".equals(t)) { break; }
          if (!",".equals(t)) {
            throw new IllegalArgumentException("Not comma: " + t);
          }
          t = toks.next();
        }
      }
      return new ProgIdSignature(Name.css(name.toString()), attrs);
    }

    /**
     * Used to generate exception messages.
     * @param it an iterator over items being processed, where the item which
     *   caused the problem is immediately behind the current posisiton.
     *   Consumed.
     * @return a string containing the items left on an iterator, separated by
     *   spaces.
     */
    private static String unroll(ListIterator<?> it) {
      if (it.hasPrevious()) { it.previous(); }
      if (!it.hasNext()) { return ""; }
      StringBuilder sb = new StringBuilder();
      sb.append(it.next());
      while (it.hasNext()) { sb.append(' ').append(it.next()); }
      return sb.toString();
    }

    private Parser() {
      // uninstantiable
    }
  }
}
