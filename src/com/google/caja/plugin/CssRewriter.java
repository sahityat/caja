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

package com.google.caja.plugin;

import com.google.caja.lang.css.CssPropertyPatterns;
import com.google.caja.lexer.ExternalReference;
import com.google.caja.lexer.FilePosition;
import com.google.caja.lexer.TokenConsumer;
import com.google.caja.parser.AncestorChain;
import com.google.caja.parser.MutableParseTreeNode;
import com.google.caja.parser.ParseTreeNode;
import com.google.caja.parser.Visitor;
import com.google.caja.parser.css.CssTree;
import com.google.caja.render.Concatenator;
import com.google.caja.render.CssPrettyPrinter;
import com.google.caja.reporting.Message;
import com.google.caja.reporting.MessageLevel;
import com.google.caja.reporting.MessagePart;
import com.google.caja.reporting.MessageQueue;
import com.google.caja.reporting.RenderContext;
import com.google.caja.util.Name;
import com.google.caja.util.Pair;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Rewrites CSS to be safer and shorter.
 * Excises disallowed constructs, removes extraneous nodes, and collapses
 * duplicate rule-set selectors.
 * <p>
 * Does not separate rules into separate name-spaces.
 *
 * @author mikesamuel@gmail.com
 */
public final class CssRewriter {
  private final PluginMeta meta;
  private final MessageQueue mq;
  private MessageLevel invalidNodeMessageLevel = MessageLevel.ERROR;

  public CssRewriter(PluginMeta meta, MessageQueue mq) {
    assert null != mq;
    assert null != meta;
    this.meta = meta;
    this.mq = mq;
  }

  /**
   * Specifies the level of messages issued when nodes are marked
   * {@link CssValidator#INVALID}.
   * If you are dealing with noisy CSS and later remove invalid nodes, then
   * this can be set to {@link MessageLevel#WARNING}.
   * @return this
   */
  public CssRewriter withInvalidNodeMessageLevel(MessageLevel messageLevel) {
    this.invalidNodeMessageLevel = messageLevel;
    return this;
  }

  /**
   * Rewrite the given CSS tree to be safer and shorter.
   *
   * If the tree could not be made safe, then there will be
   * {@link MessageLevel#ERROR error}s on the {@link MessageQueue} passed
   * to the constructor.
   *
   * @param t non null.  modified in place.
   */
  public void rewrite(AncestorChain<? extends CssTree> t) {
    rewriteHistorySensitiveRulesets(t);
    quoteLooseWords(t);
    fixUnitlessLengths(t);
    // Once at the beginning, and again at the end.
    removeUnsafeConstructs(t);
    removeEmptyDeclarationsAndSelectors(t);
    // After we remove declarations, we may have some rulesets without any
    // declarations which is technically illegal, so we remove rulesets without
    // declarations.
    removeEmptyRuleSets(t);
    // Disallow classes and IDs that end in double underscore.
    removeForbiddenIdents(t);
    // Do this again to make sure no earlier changes introduce unsafe constructs
    removeUnsafeConstructs(t);

    translateUrls(t);
  }

  /**
   * A set of pseudo classes that are allowed in restricted context because they
   * can leak user history information.
   * <p>
   * From http://www.w3.org/TR/css3-selectors/#dynamic-pseudos : <blockquote>
   *   <h3>6.6.1. Dynamic pseudo-classes</h3>
   *   The link pseudo-classes: :link and :visited<br>
   *   <br>
   *   User agents commonly display unvisited links differently from previously
   *   visited ones. Selectors provides the pseudo-classes :link and :visited to
   *   distinguish them:<ul>
   *     <li>The :link pseudo-class applies to links that have not yet been
   *         visited.
   *     <li>The :visited pseudo-class applies once the link has been visited by
   *         the user.
   *   </ul>
   * </blockquote>
   */
  private static final Set<Name> LINK_PSEUDO_CLASSES = new HashSet<Name>(
      Arrays.asList(Name.css("link"), Name.css("visited")));

  /**
   * Split any ruleset containing :link or :visited pseudoclasses into two
   * rulesets: one with these pseudoclasses in the selector, and one without.
   * (One of these resulting rulesets may be empty and thus not emitted.) So
   * for example, the stylesheet:
   *
   * <pre>
   *   :visited, a:link, p, div { color: blue }
   * </pre>
   *
   * <p>becomes:
   *
   * <pre>
   *   :visited, a:link { color: blue }
   *   p, div { color: blue }
   * </pre>
   *
   * <p>We do this because, downstream, we are going to cull away declarations
   * for properties which are not permitted to depend on the :link or :visisted
   * pseudoclasses. We do this, in turn, to prevent history mining attacks.
   *
   * <p>Furthermore, scope any selectors containing linkey pseudo classes to
   * operate only on anchor (<A>) elements. Modify it if necessary, or record
   * an error if the selector is already scoped to some element that is not an
   * anchor. For example:
   *
   * <pre>
   *   div#foo     -->  div#foo     (unmodified)
   *   :visited    -->  a:visited
   *   :link       -->  a:link
   *   *:visited   -->  a:visited
   *   p:visited   -->  ERROR
   * </pre>
   *
   * <p>We do this to ensure the most predictable possible browser behavior
   * around this sensitive and exploitable issue.
   */
  private void rewriteHistorySensitiveRulesets(
      final AncestorChain<? extends CssTree> t) {
    t.node.acceptPreOrder(new Visitor() {
        public boolean visit(AncestorChain<?> ancestors) {
          if (!(ancestors.node instanceof CssTree.RuleSet)) { return true; }
          Pair<CssTree.RuleSet, CssTree.RuleSet> rewritten =
              rewriteHistorySensitiveRuleset((CssTree.RuleSet) ancestors.node);
          if (rewritten != null) {
            t.node.insertBefore(rewritten.a, ancestors.node);
            t.node.insertBefore(rewritten.b, ancestors.node);
            t.node.removeChild(ancestors.node);
          }
          return false;
        }
      }, t.parent);
  }

  private Pair<CssTree.RuleSet, CssTree.RuleSet> rewriteHistorySensitiveRuleset(
      CssTree.RuleSet ruleSet) {
    List<CssTree> linkeyChildren = null;
    List<CssTree> nonLinkeyChildren = null;

    for (CssTree child : ruleSet.children()) {
      if (child instanceof CssTree.Selector) {
        CssTree.Selector selector = (CssTree.Selector) child;
        if (addAnchorToHistorySensitiveSelector(selector)) {
          if (linkeyChildren == null) {
            linkeyChildren = new ArrayList<CssTree>();
          }
          linkeyChildren.add(selector);
        }
        else {
          if (nonLinkeyChildren == null) {
            nonLinkeyChildren = new ArrayList<CssTree>();
          }
          nonLinkeyChildren.add(selector);
        }
      } else {
        if (linkeyChildren == null || nonLinkeyChildren == null) {
          return null;
        } else {
          linkeyChildren.add(child);
          nonLinkeyChildren.add((CssTree) child.clone());
        }
      }
    }

    return new Pair<CssTree.RuleSet, CssTree.RuleSet>(
        new CssTree.RuleSet(ruleSet.getFilePosition(), linkeyChildren),
        new CssTree.RuleSet(ruleSet.getFilePosition(), nonLinkeyChildren));
  }

  /** Argument is a compound selector like "div#foo > p > *:visited". */
  private boolean addAnchorToHistorySensitiveSelector(
      CssTree.Selector selector) {
    boolean modified = false;
    for (CssTree child : selector.children()) {
      if (child instanceof CssTree.SimpleSelector) {
        modified |= addAnchorToHistorySensitiveSimpleSelector(
            (CssTree.SimpleSelector) child);
      }
    }
    return modified;
  }

  /** The name of an anchor (<A>) HTML tag. */
  private static final Name HTML_ANCHOR = Name.html("a");

  /** Argument is a simple selector like "div#foo", "p", or "*:visited". */
  private boolean addAnchorToHistorySensitiveSimpleSelector(
      CssTree.SimpleSelector selector) {
    if (selector.children().isEmpty()) { return false; }
    if (!containsLinkPseudoClass(selector)) { return false; }
    CssTree firstChild = selector.children().get(0);
    if (firstChild instanceof CssTree.WildcardElement) {
      // "*#foo:visited" --> "a#foo:visited"
      selector.replaceChild(
          new CssTree.IdentLiteral(
              firstChild.getFilePosition(), HTML_ANCHOR.getCanonicalForm()),
          firstChild);
      return true;
    } else if (firstChild instanceof CssTree.IdentLiteral) {
      // "a#foo:visited" is legal; "p#foo:visited" is not
      String value = ((CssTree.IdentLiteral) firstChild).getValue();
      if (HTML_ANCHOR.compareTo(Name.html(value)) != 0) {
        mq.addMessage(
            PluginMessageType.CSS_LINK_PSEUDO_SELECTOR_NOT_ALLOWED_ON_NONANCHOR,
            firstChild.getFilePosition());
      }
      return false;
    } else {
      // "#foo:visited" --> "a#foo:visited"
      selector.insertBefore(
          new CssTree.IdentLiteral(
              firstChild.getFilePosition(), HTML_ANCHOR.getCanonicalForm()),
          firstChild);
      return true;
    }
  }

  private boolean containsLinkPseudoClass(CssTree.SimpleSelector selector) {
    final boolean[] result = new boolean[1];
    selector.acceptPreOrder(new Visitor() {
      public boolean visit(AncestorChain<?> chain) {
        if (chain.node instanceof CssTree.Pseudo) {
          CssTree firstChild = (CssTree) chain.node.children().get(0);
          if (firstChild instanceof CssTree.IdentLiteral) {
            CssTree.IdentLiteral ident = (CssTree.IdentLiteral) firstChild;
            if (LINK_PSEUDO_CLASSES.contains(Name.css(ident.getValue()))) {
              result[0] = true;
              return false;
            }
          }
        }
        return true;
      }
    }, null);
    return result[0];
  }

  /**
   * Turn a run of unquoted identifiers into a single string, where the property
   * description says "Names containing space *should* be quoted", but does not
   * require it.
   * <p>
   * This is important for font {@code family-name}s where
   * {@code font: Times New Roman} should be written as
   * {@code font: "Times New Roman"} to avoid any possible ambiguity between
   * the individual terms and special values such as {@code serif}.
   *
   * @see CssPropertyPartType#LOOSE_WORD
   */
  private void quoteLooseWords(AncestorChain<? extends CssTree> t) {
    if (t.node instanceof CssTree.Expr) {
      combineLooseWords(t.cast(CssTree.Expr.class).node);
    }
    for (CssTree child : t.node.children()) {
      quoteLooseWords(AncestorChain.instance(t, child));
    }
  }

  private void combineLooseWords(CssTree.Expr e) {
    for (int i = 0, n = e.getNTerms(); i < n; ++i) {
      CssTree.Term t = e.getNthTerm(i);
      if (!isLooseWord(t)) { continue; }

      Name propertyPart = t.getAttributes().get(CssValidator.CSS_PROPERTY_PART);
      StringBuilder sb = new StringBuilder();
      sb.append(t.getExprAtom().getValue());

      // Compile a mutation that removes all the extraneous terms and that
      // replaces t with a string literal.
      MutableParseTreeNode.Mutation mut = e.createMutation();

      // Compute end, the term index after the last of the run of loose terms
      // for t's property part.
      int start = i;
      int end = i + 1;
      while (end < n) {
        CssTree.Operation op = e.getNthOperation(end - 1);
        CssTree.Term t2 = e.getNthTerm(end);
        if (!(CssTree.Operator.NONE == op.getOperator() && isLooseWord(t2)
              && propertyPart.equals(
                     t2.getAttributes().get(CssValidator.CSS_PROPERTY_PART)))) {
          break;
        }
        mut.removeChild(op);
        mut.removeChild(t2);
        sb.append(' ').append(e.getNthTerm(end).getExprAtom().getValue());
        ++end;
      }

      // Create a string literal to replace all the terms [start:end-1].
      // Make sure it has the same synthetic attributes and file position.
      String text = sb.toString();
      FilePosition pos = FilePosition.span(
          t.getFilePosition(), e.getNthTerm(end - 1).getFilePosition());
      CssTree.StringLiteral quotedWords = new CssTree.StringLiteral(pos, text);
      CssTree.Term quotedTerm = new CssTree.Term(pos, null, quotedWords);
      quotedTerm.getAttributes().putAll(t.getAttributes());
      quotedTerm.getAttributes().set(CssValidator.CSS_PROPERTY_PART_TYPE,
                                     CssPropertyPartType.STRING);

      mut.replaceChild(quotedTerm, t);
      mut.execute();

      // If we made a substantive change, combining multiple terms into one,
      // then issue a line message.  We don't need to issue a warning on all
      // changes, since we only reach this code if we passed validation.
      if (end - start > 1) {
        mq.addMessage(PluginMessageType.QUOTED_CSS_VALUE,
                      pos, MessagePart.Factory.valueOf(text));
      }

      n = e.getNTerms();
    }
  }

  /** @see CssPropertyPartType#LOOSE_WORD */
  private static boolean isLooseWord(CssTree.Term t) {
    return t.getOperator() == null
        && t.getExprAtom() instanceof CssTree.IdentLiteral
        && (t.getAttributes().get(CssValidator.CSS_PROPERTY_PART_TYPE)
            == CssPropertyPartType.LOOSE_WORD);
  }

  /**
   * <a href="http://www.w3.org/TR/CSS21/syndata.html#length-units">Lengths</a>
   * require units unless the value is zero.  All browsers assume px if the
   * suffix is missing.
   */
  private void fixUnitlessLengths(AncestorChain<? extends CssTree> t) {
    t.node.acceptPreOrder(new Visitor() {
        public boolean visit(AncestorChain<?> ancestors) {
          if (!(ancestors.node instanceof CssTree.Term)) {
            return true;
          }
          CssTree.Term term = (CssTree.Term) ancestors.node;
          if (!(CssPropertyPartType.LENGTH == term.getAttributes().get(
                    CssValidator.CSS_PROPERTY_PART_TYPE)
                && term.getExprAtom() instanceof CssTree.QuantityLiteral)) {
            return true;
          }
          CssTree.QuantityLiteral quantity = (CssTree.QuantityLiteral)
              term.getExprAtom();
          String value = quantity.getValue();
          if (!isZeroOrHasUnits(value)) {
            // Missing units.
            CssTree.QuantityLiteral withUnits = new CssTree.QuantityLiteral(
                quantity.getFilePosition(), value + "px");
            withUnits.getAttributes().putAll(quantity.getAttributes());
            term.replaceChild(withUnits, quantity);
            mq.addMessage(PluginMessageType.ASSUMING_PIXELS_FOR_LENGTH,
                          quantity.getFilePosition(),
                          MessagePart.Factory.valueOf(value));
          }
          return false;
        }
      }, t.parent);
  }
  private static boolean isZeroOrHasUnits(String value) {
    int len = value.length();
    char ch = value.charAt(len - 1);
    if (ch == '.' || ('0' <= ch && ch <= '9')) {  // Missing units
      for (int i = len; --i >= 0;) {
        ch = value.charAt(i);
        if ('1' <= ch && ch <= '9') { return false; }
      }
    }
    return true;
  }

  /** Get rid of rules like <code>p { }</code>. */
  private void removeEmptyDeclarationsAndSelectors(
      AncestorChain<? extends CssTree> t) {
    t.node.acceptPreOrder(new Visitor() {
        public boolean visit(AncestorChain<?> ancestors) {
          ParseTreeNode node = ancestors.node;
          if (node instanceof CssTree.EmptyDeclaration) {
            ParseTreeNode parent = ancestors.getParentNode();
            if (parent instanceof MutableParseTreeNode) {
              ((MutableParseTreeNode) parent).removeChild(node);
            }
            return false;
          } else if (node instanceof CssTree.Selector) {
            CssTree.Selector sel = (CssTree.Selector) node;
            if (sel.children().isEmpty()
                || !(sel.children().get(0) instanceof CssTree.SimpleSelector)) {
              // Remove from parent
              ParseTreeNode parent = ancestors.getParentNode();
              if (parent instanceof MutableParseTreeNode) {
                ((MutableParseTreeNode) parent).removeChild(sel);
              }
            }
            return false;
          }
          return true;
        }
      }, t.parent);
  }
  private void removeEmptyRuleSets(AncestorChain<? extends CssTree> t) {
    t.node.acceptPreOrder(new Visitor() {
        public boolean visit(AncestorChain<?> ancestors) {
          ParseTreeNode node = ancestors.node;
          if (!(node instanceof CssTree.RuleSet)) { return true; }
          CssTree.RuleSet rset = (CssTree.RuleSet) node;
          List<? extends CssTree> children = rset.children();
          if (children.isEmpty()
              || (children.get(children.size() - 1)
                  instanceof CssTree.Selector)
              || !(children.get(0) instanceof CssTree.Selector)) {
            // No declarations or no selectors, so either the properties apply
            // to nothing or there are no properties to apply.
            ParseTreeNode parent = ancestors.getParentNode();
            if (parent instanceof MutableParseTreeNode) {
              ((MutableParseTreeNode) parent).removeChild(rset);
            }
          }
          return false;
        }
      }, t.parent);
  }
  private void removeForbiddenIdents(AncestorChain<? extends CssTree> t) {
    t.node.acceptPreOrder(new Visitor() {
        public boolean visit(AncestorChain<?> ac) {
          if (!(ac.node instanceof CssTree.SimpleSelector)) { return true; }
          CssTree.SimpleSelector ss = (CssTree.SimpleSelector) ac.node;
          boolean ok = false;
          for (CssTree child : ss.children()) {
            if (child instanceof CssTree.ClassLiteral
                || child instanceof CssTree.IdLiteral) {
              String literal = (String) child.getValue();
              if (literal.endsWith("__")) {
                mq.addMessage(PluginMessageType.UNSAFE_CSS_IDENTIFIER,
                    child.getFilePosition(),
                    MessagePart.Factory.valueOf(literal));
                ac.parent.node.getAttributes().set(CssValidator.INVALID, true);
                ok = false;
              }
            }
          }
          return ok;
        }
      }, t.parent);
  }

  private static final Set<Name> ALLOWED_PSEUDO_CLASSES = new HashSet<Name>(
      Arrays.asList(
          Name.css("active"), Name.css("after"), Name.css("before"),
          Name.css("first-child"), Name.css("first-letter"), Name.css("focus"),
          Name.css("link"), Name.css("hover")
          ));
  void removeUnsafeConstructs(AncestorChain<? extends CssTree> t) {

    // 1) Check that all classes, ids, property names, etc. are valid
    //    css identifiers.
    t.node.acceptPreOrder(new Visitor() {
        public boolean visit(AncestorChain<?> ancestors) {
          ParseTreeNode node = ancestors.node;
          if (node instanceof CssTree.SimpleSelector) {
            for (CssTree child : ((CssTree.SimpleSelector) node).children()) {
              if (child instanceof CssTree.Pseudo) {
                child = child.children().get(0);
                // TODO(mikesamuel): check argument if child now a FunctionCall
              }
              Object value = child.getValue();
              if (value != null && !isSafeSelectorPart(value.toString())) {
                mq.addMessage(PluginMessageType.UNSAFE_CSS_IDENTIFIER,
                              child.getFilePosition(),
                              MessagePart.Factory.valueOf(value.toString()));
                // Will be deleted by a later pass after all messages have been
                // generated
                node.getAttributes().set(CssValidator.INVALID, Boolean.TRUE);
                return false;
              }
            }
          }
          // The CssValidator checks the safety of CSS property names.
          return true;
        }
      }, t.parent);

    // 2) Ban content properties, and attr pseudo classes, and any other
    //    pseudo selectors that don't match the whitelist
    t.node.acceptPreOrder(new Visitor() {
        public boolean visit(AncestorChain<?> ancestors) {
          ParseTreeNode node = ancestors.node;
          if (node instanceof CssTree.Pseudo) {
            boolean remove = false;
            CssTree child = ((CssTree.Pseudo) node).children().get(0);
            if (child instanceof CssTree.IdentLiteral) {
              Name pseudoName = Name.css(
                  ((CssTree.IdentLiteral) child).getValue());
              if (!ALLOWED_PSEUDO_CLASSES.contains(pseudoName)) {
                // Allow the visited pseudo selector but not with any styles
                // that are fetchable via getComputedStyle in DOMita's
                // COMPUTED_STYLE_WHITELIST.
                if (!(LINK_PSEUDO_CLASSES.contains(pseudoName)
                      && strippedPropertiesBannedInLinkClasses(
                          ancestors.parent.parent.cast(CssTree.Selector.class)
                          ))) {
                  mq.addMessage(PluginMessageType.UNSAFE_CSS_PSEUDO_SELECTOR,
                                invalidNodeMessageLevel, node.getFilePosition(),
                                node);
                  remove = true;
                }
              }
            } else {
              StringBuilder rendered = new StringBuilder();
              TokenConsumer tc = new CssPrettyPrinter(
                  new Concatenator(rendered));
              node.render(new RenderContext(tc));
              tc.noMoreTokens();
              mq.addMessage(PluginMessageType.UNSAFE_CSS_PSEUDO_SELECTOR,
                            invalidNodeMessageLevel, node.getFilePosition(),
                            MessagePart.Factory.valueOf(rendered.toString()));
              remove = true;
            }
            if (remove) {
              // Delete the containing selector, since otherwise we'd broaden
              // the rule.
              selectorFor(ancestors).getAttributes().set(
                  CssValidator.INVALID, Boolean.TRUE);
            }
          }
          return true;
        }
      }, t.parent);
    // 3) Remove any properties and attributes that didn't validate
    t.node.acceptPreOrder(new Visitor() {
        public boolean visit(AncestorChain<?> ancestors) {
          ParseTreeNode node = ancestors.node;
          if (node instanceof CssTree.Property) {
            if (node.getAttributes().is(CssValidator.INVALID)) {
              declarationFor(ancestors).getAttributes().set(
                  CssValidator.INVALID, Boolean.TRUE);
            }
          } else if (node instanceof CssTree.Attrib) {
            if (node.getAttributes().is(CssValidator.INVALID)) {
              simpleSelectorFor(ancestors).getAttributes().set(
                  CssValidator.INVALID, Boolean.TRUE);
            }
          } else if (node instanceof CssTree.Term
                     && (CssPropertyPartType.URI ==
                         node.getAttributes().get(
                             CssValidator.CSS_PROPERTY_PART_TYPE))) {

            boolean remove = false;
            Message removeMsg = null;

            CssTree term = (CssTree.Term) node;
            CssTree.CssLiteral content =
                (CssTree.CssLiteral) term.children().get(0);

            if (content instanceof CssTree.Substitution) {
              return true;  // Handled by later pass.
            }

            String uriStr = content.getValue();
            try {
              URI baseUri = content.getFilePosition().source().getUri();
              URI uri = baseUri.resolve(new URI(uriStr));
              ExternalReference ref = new ExternalReference(
                  uri, content.getFilePosition());
              if (meta.getPluginEnvironment().rewriteUri(ref, "image/*")
                  == null) {
                removeMsg = new Message(
                    PluginMessageType.DISALLOWED_URI,
                    node.getFilePosition(),
                    MessagePart.Factory.valueOf(uriStr));
                remove = true;
              }
            } catch (URISyntaxException ex) {
              removeMsg = new Message(
                  PluginMessageType.DISALLOWED_URI,
                  node.getFilePosition(), MessagePart.Factory.valueOf(uriStr));
              remove = true;
            }

            if (remove) {
              // condemn the containing declaration
              CssTree.Declaration decl = declarationFor(ancestors);
              if (null != decl) {
                if (!decl.getAttributes().is(CssValidator.INVALID)) {
                  if (null != removeMsg) { mq.getMessages().add(removeMsg); }
                  decl.getAttributes().set(CssValidator.INVALID, Boolean.TRUE);
                }
              }
            }
          }
          return true;
        }
      }, t.parent);

    // 4) Remove invalid nodes
    removeInvalidNodes(t);

    // 5) Cleanup.  Remove any rulesets with empty selectors
    // Since this is a post order traversal, we will first remove empty
    // selectors, and then consider any rulesets that have become empty due to
    // a lack of selectors.
    t.node.acceptPreOrder(new Visitor() {
        public boolean visit(AncestorChain<?> ancestors) {
          ParseTreeNode node = ancestors.node;
          if ((node instanceof CssTree.Selector && node.children().isEmpty())
              || (node instanceof CssTree.RuleSet
                  && (node.children().isEmpty()
                      || node.children().get(0) instanceof CssTree.Declaration))
              ) {
            ((MutableParseTreeNode) ancestors.parent.node).removeChild(node);
            return false;
          }
          return true;
        }
      }, t.parent);
  }

  private void removeInvalidNodes(AncestorChain<? extends CssTree> t) {
    if (t.node.getAttributes().is(CssValidator.INVALID)) {
      ((MutableParseTreeNode) t.parent.node).removeChild(t.node);
      return;
    }

    // Use a mutation to remove invalid nodes so that the sanity checks in
    // childrenChanged sees all removals at once.
    MutableParseTreeNode.Mutation mut = null;
    for (CssTree child : t.node.children()) {
      if (child.getAttributes().is(CssValidator.INVALID)) {
        if (mut == null) { mut = t.node.createMutation(); }
        mut.removeChild(child);
      } else {
        removeInvalidNodes(AncestorChain.instance(t, child));
      }
    }
    if (mut != null) { mut.execute(); }
  }

  private void translateUrls(AncestorChain<? extends CssTree> t) {
      t.node.acceptPreOrder(new Visitor() {
          public boolean visit(AncestorChain<?> ancestors) {
            ParseTreeNode node = ancestors.node;
            if (node instanceof CssTree.Term
                && CssPropertyPartType.URI == propertyPartType(node)) {
              CssTree term = (CssTree.Term) node;

              CssTree.CssLiteral content =
                  (CssTree.CssLiteral) term.children().get(0);
              if (content instanceof CssTree.Substitution) {
                return true;  // Handled by later pass.
              }

              String uriStr = content.getValue();
              try {
                URI baseUri = content.getFilePosition().source().getUri();
                URI uri = baseUri.resolve(new URI(uriStr));
                // Rewrite the URI.
                // TODO(mikesamuel): for content: and other URI types, use
                // mime-type of text/*.
                ExternalReference ref = new ExternalReference(
                    uri, content.getFilePosition());
                String rewrittenUri = meta.getPluginEnvironment().rewriteUri(
                    ref, "image/*");
                CssTree.UriLiteral replacement = new CssTree.UriLiteral(
                        content.getFilePosition(), URI.create(rewrittenUri));
                replacement.getAttributes().putAll(content.getAttributes());
                term.replaceChild(replacement, content);
              } catch (URISyntaxException ex) {
                // Should've been checked in removeUnsafeConstructs.
                throw new AssertionError();
              }
            }
            return true;
          }
        }, t.parent);
  }

  private static CssTree.Declaration declarationFor(AncestorChain<?> chain) {
    for (AncestorChain<?> c = chain; null != c; c = c.parent) {
      if (c.node instanceof CssTree.Declaration) {
        return (CssTree.Declaration) c.node;
      }
    }
    return null;
  }

  private static CssTree.SimpleSelector simpleSelectorFor(
      AncestorChain<?> chain) {
    for (AncestorChain<?> c = chain; null != c; c = c.parent) {
      if (c.node instanceof CssTree.SimpleSelector) {
        return (CssTree.SimpleSelector) c.node;
      }
    }
    return null;
  }

  private static CssTree.Selector selectorFor(AncestorChain<?> chain) {
    for (AncestorChain<?> c = chain; null != c; c = c.parent) {
      if (c.node instanceof CssTree.Selector) {
        return (CssTree.Selector) c.node;
      }
    }
    return null;
  }

  private static final Set<Name> PROPERTIES_ALLOWED_IN_LINK_CLASSES;
  static {
    Set<Name> propNames = new HashSet<Name>(Arrays.asList(
        Name.css("background-color"), Name.css("color"), Name.css("cursor")));
    // Rules limited to link and visited styles cannot allow properties that
    // can be tested by DOMita's getComputedStyle since it would allow history
    // mining.
    // Do not inline the below.  The removeAll relies on the input being a set
    // of names, but since removeAll takes a Collection<?> it would fail
    // silently if the whitelist were changed to a Collection<String>.
    // Assigning to a local does type-check though.
    Set<Name> computedStyleNames =
        CssPropertyPatterns.HISTORY_INSENSITIVE_STYLE_WHITELIST;
    propNames.removeAll(computedStyleNames);
    PROPERTIES_ALLOWED_IN_LINK_CLASSES = Collections.unmodifiableSet(propNames);
  }
  private boolean strippedPropertiesBannedInLinkClasses(
      AncestorChain<CssTree.Selector> sel) {
    if (!(sel.parent.node instanceof CssTree.RuleSet)) { return false; }
    Set<Name> propertyNames = PROPERTIES_ALLOWED_IN_LINK_CLASSES;
    CssTree.RuleSet rs = sel.parent.cast(CssTree.RuleSet.class).node;
    MutableParseTreeNode.Mutation mut = rs.createMutation();
    for (CssTree child : rs.children()) {
      if (child instanceof CssTree.Selector
          || child instanceof CssTree.EmptyDeclaration) {
        continue;
      }
      CssTree.PropertyDeclaration pd;
      if (child instanceof CssTree.PropertyDeclaration) {
        pd = (CssTree.PropertyDeclaration) child;
      } else {
        pd = ((CssTree.UserAgentHack) child).getDeclaration();
      }
      CssTree.Property p = pd.getProperty();
      Name propName = p.getPropertyName();
      boolean allowedInLinkClass = propertyNames.contains(propName);
      if (!allowedInLinkClass && propName.getCanonicalForm().startsWith("_")) {
        allowedInLinkClass = propertyNames.contains(Name.css(
            propName.getCanonicalForm().substring(1)));
      }
      if (!allowedInLinkClass || mightContainUrl(pd.getExpr())) {
        mq.getMessages().add(new Message(
            PluginMessageType.DISALLOWED_CSS_PROPERTY_IN_SELECTOR,
            this.invalidNodeMessageLevel,
            p.getFilePosition(), p.getPropertyName(),
            sel.node.getFilePosition()));
        mut.removeChild(child);
      }
    }
    mut.execute();
    return true;
  }

  private boolean mightContainUrl(CssTree.Expr expr) {
    for (int n = expr.getNTerms(), i = 0; i < n; ++i) {
      CssTree.CssExprAtom atom = expr.getNthTerm(i).getExprAtom();
      if (!(atom instanceof CssTree.IdentLiteral
            || atom instanceof CssTree.QuantityLiteral
            || atom instanceof CssTree.HashLiteral)) {
        return true;
      }
    }
    return false;
  }

  private static final Pattern SAFE_SELECTOR_PART
      = Pattern.compile("^[#!\\.]?[a-zA-Z][_a-zA-Z0-9\\-]*$");
  /**
   * Restrict selectors to ascii characters until we can test browser handling
   * of escape sequences.
   */
  private static boolean isSafeSelectorPart(String s) {
    return SAFE_SELECTOR_PART.matcher(s).matches();
  }

  private static CssPropertyPartType propertyPartType(ParseTreeNode node) {
    return node.getAttributes().get(CssValidator.CSS_PROPERTY_PART_TYPE);
  }
}
