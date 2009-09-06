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

import com.google.caja.lang.html.HTML;
import com.google.caja.lexer.FilePosition;
import com.google.caja.parser.html.Nodes;
import com.google.caja.reporting.MessagePart;
import com.google.caja.reporting.MessageQueue;
import com.google.caja.util.Name;

import java.util.ArrayList;
import java.util.List;

import org.w3c.dom.Attr;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * Used to check that the IHTML output of the TemplateCompiler satisfies basic
 * well-formedness constraints.
 *
 * @author mikesamuel@gmail.com
 */
public class IhtmlSanityChecker {
  private final MessageQueue mq;

  /** @param mq receives warnings and errors about invalid IHTML constructs. */
  public IhtmlSanityChecker(MessageQueue mq) {
    assert mq != null;
    this.mq = mq;
  }

  public boolean check(Element ihtmlRoot) {
    checkIhtmlElements(ihtmlRoot);
    checkDynamicDomParents(ihtmlRoot);
    disallowNestedMessages(ihtmlRoot);
    disallowPlaceholderContent(ihtmlRoot);
    disallowIhtmlInMessageOutsidePlaceholders(ihtmlRoot, false);
    checkPlaceholdersBalanced(ihtmlRoot);

    removeBrokenNodes(ihtmlRoot);
    return !isBroken(ihtmlRoot);
  }

  /**
   * Make sure that {@code <ihtml:*>} elements have the appropriate attributes.
   * Marks as broken any elements with bad attributes and reports an error or
   * warning about broken elements.
   */
  private void checkIhtmlElements(Element ihtmlRoot) {
    for (Element msg : allIhtml(ihtmlRoot)) {
      Name elName = Name.xml(msg.getTagName());
      if (!IHTML.SCHEMA.isElementAllowed(elName)) {
        mq.addMessage(
            IhtmlMessageType.BAD_ELEMENT, Nodes.getFilePositionFor(msg),
            elName);
        markBroken(msg);
        continue;
      }
      HTML.Element elDetails = IHTML.SCHEMA.lookupElement(elName);
      for (HTML.Attribute attrDetails : elDetails.getAttributes()) {
        if (attrDetails.isOptional()) { continue; }
        Name attrName = attrDetails.getAttributeName();
        if (!msg.hasAttribute(attrName.getCanonicalForm())) {
          mq.addMessage(
              IhtmlMessageType.MISSING_ATTRIB, Nodes.getFilePositionFor(msg),
              elName, attrName);
          markBroken(msg);
        }
      }
      for (Attr a : Nodes.attributesOf(msg)) {
        Name attrName = Name.xml(a.getName());
        if ((IHTML.PREFIX + ":call").equals(elName.getCanonicalForm())
            && IHTML.isSafeIdentifier(attrName.getCanonicalForm())) {
          continue;
        }
        HTML.Attribute attrDetails = IHTML.SCHEMA.lookupAttribute(
            elName, attrName);
        if (!IHTML.SCHEMA.isAttributeAllowed(elName, attrName)
            || !attrDetails.getValueCriterion().accept(a.getValue())) {
          mq.addMessage(
              IhtmlMessageType.BAD_ATTRIB, posOf(a),
              elName,
              attrName,
              MessagePart.Factory.valueOf(a.getValue()));
          markBroken(msg);
        }
      }
    }
  }

  private void checkDynamicDomParents(Element ihtmlRoot) {
    for (String elementName : new String[] { "attribute", "element" }) {
      for (Element iel : IHTML.allOf(ihtmlRoot, elementName)) {
        for (Node p = iel; (p = p.getParentNode()) != null;) {
          if (IHTML.isIhtml(p)
              && !(IHTML.isTemplate(p) || IHTML.isDo(p) || IHTML.isElse(p))) {
            mq.addMessage(
                IhtmlMessageType.MISPLACED_ELEMENT,
                Nodes.getFilePositionFor(iel),
                Name.xml(iel.getNodeName()),
                Name.xml(p.getNodeName()));
            markBroken(iel);
          }
        }
      }
    }
  }

  /**
   * Make sure that no {@code <ihtml:message>}s are contained in another
   * message, warning about and marking as broken any that are.
   */
  private void disallowNestedMessages(Element ihtmlRoot) {
    for (Element msg : IHTML.allOf(ihtmlRoot, "message")) {
      for (Node p = msg; (p = p.getParentNode()) != null;) {
        if (IHTML.isMessage(p)) {
          mq.addMessage(
              IhtmlMessageType.NESTED_MESSAGE,
              Nodes.getFilePositionFor(msg),
              Nodes.getFilePositionFor(p));
          markBroken(msg);
        }
      }
    }
  }

  private void disallowPlaceholderContent(Element ihtmlRoot) {
    for (Element el : IHTML.getPlaceholders(ihtmlRoot)) {
      if (el.getFirstChild() != null) {
        mq.addMessage(
            IhtmlMessageType.INAPPROPRIATE_CONTENT,
            FilePosition.span(
                Nodes.getFilePositionFor(el.getFirstChild()),
                Nodes.getFilePositionFor(el.getLastChild())),
            MessagePart.Factory.valueOf(el.getTagName())
            );
        markBroken(el);
      }
    }
  }

  /**
   * Mark as broken and warn about any placeholders not followed by an
   * {@code <ihtml:eph/>}.
   */
  private void checkPlaceholdersBalanced(Element ihtmlRoot) {
    // Make sure that <ihtml:ph> and <ihtml:eph> elements match up properly.
    // Each <ihtml:ph> must be followed (in a DFS traversal) by an <ihtml:eph>
    // element without an intervening <ihtml:ph>.
    boolean expectOpen = true;    // True iff outside a placeholder
    Element last = null;    // The last ph or eph element processed
    Element lastContainer = null;    // The message containing last
    for (Element el : IHTML.getPlaceholders(ihtmlRoot)) {
      if (isBroken(el)) { continue; }
      Element msg = null;
      // Find the containing message.
      for (Node p = el; (p = p.getParentNode()) != null;) {
        if (IHTML.isMessage(p)) {
          msg = (Element) p;
          break;
        } else if (isIhtml(p)) {
          // There must be no IHTML elements on the ancestor path between a
          // placeholder and the containing message.
          break;
        }
      }
      if (msg == null) {  // Outside a message
        mq.addMessage(
            IhtmlMessageType.ORPHANED_PLACEHOLDER,
            Nodes.getFilePositionFor(el));
        markBroken(el);
        continue;
      }

      if (msg != lastContainer) {
        // Found a different message.
        if (!expectOpen) {
          // last must be an ihtml:ph without a corresponding ihtml:eph.
          markBroken(last);
          FilePosition endPos = FilePosition.endOf(
              Nodes.getFilePositionFor(lastContainer.getLastChild()));
          mq.addMessage(
              IhtmlMessageType.UNCLOSED_PLACEHOLDER,
              FilePosition.span(Nodes.getFilePositionFor(last), endPos));
          markBroken(last);
          expectOpen = true;
          last = null;
        }
        lastContainer = msg;
      }

      if (expectOpen != IHTML.isPh(el)) {
        if (expectOpen) {
          mq.addMessage(
              IhtmlMessageType.ORPHANED_PLACEHOLDER_END,
              Nodes.getFilePositionFor(el));
          markBroken(el);
        } else {
          mq.addMessage(
              IhtmlMessageType.UNCLOSED_PLACEHOLDER,
              FilePosition.span(
                  Nodes.getFilePositionFor(last),
                  FilePosition.startOf(Nodes.getFilePositionFor(el))));
          markBroken(last);
          last = el;
        }
      } else {
        last = el;
        expectOpen = !expectOpen;
      }
    }

    // Check that the last item is not an unclosed ihtml:ph.
    if (!expectOpen) {
      FilePosition endPos = FilePosition.endOf(
          Nodes.getFilePositionFor(lastContainer.getLastChild()));
      mq.addMessage(
          IhtmlMessageType.UNCLOSED_PLACEHOLDER,
          FilePosition.span(Nodes.getFilePositionFor(last), endPos));
      markBroken(last);
    }

    // Now IHTML.getPlaceholders(ihtmlRoot) with broken elements removed
    // will return a sequence such that:
    // (1) Its length is even
    // (2) Every ihtml:ph element in the sequence is followed by an ihtml:eph
    //     that is a descendant of the same ihtml:message.
    // (3) Every ihtml:eph element is preceded by an ihtml:ph element that is
    //     a descendant of the same ihtml:message.
  }

  /**
   * Make sure that all IHTML elements inside messages appear inside a
   * placeholder.  Any that don't cause a warning and cause the containing
   * message to be marked broken.
   */
  private void disallowIhtmlInMessageOutsidePlaceholders(
      Element el, boolean inMessage) {
    if (isBroken(el)) { return; }
    if (isIhtml(el)) {
      if (IHTML.isMessage(el)) {
        inMessage = true;
      } else if (inMessage) {
        mq.addMessage(
            IhtmlMessageType.IHTML_IN_MESSAGE_OUTSIDE_PLACEHOLDER,
            Nodes.getFilePositionFor(el),
            Name.xml(el.getTagName()));
        for (Node p = el; (p = p.getParentNode()) != null;) {
          if (IHTML.isMessage(p)) {
            markBroken(p);
            break;
          }
        }
      }
    }
    boolean inPlaceholder = false;
    for (Node c = el.getFirstChild(); c != null; c = c.getNextSibling()) {
      if (c instanceof Element) {
        Element cEl = (Element) c;
        if (IHTML.isPh(cEl)) {
          inPlaceholder = inMessage;
        } else if (IHTML.isEph(cEl)) {
          inPlaceholder = false;
        } else if (!inPlaceholder) {
          disallowIhtmlInMessageOutsidePlaceholders(cEl, inMessage);
        }
      }
    }
  }

  private void removeBrokenNodes(Element ihtmlRoot) {
    List<Element> broken = new ArrayList<Element>();
    for (Element e : Nodes.nodeListIterable(
             ihtmlRoot.getElementsByTagName("*"), Element.class)) {
      if (isBroken(e)) { broken.add(e); }
    }
    for (Element e : broken) {
      e.getParentNode().removeChild(e);
    }
  }

  private static final String BROKEN_NODE = "broken_node";
  private static void markBroken(Node node) {
    node.setUserData(BROKEN_NODE, Boolean.TRUE, null);
  }

  private static boolean isBroken(Node node) {
    return Boolean.TRUE.equals(node.getUserData(BROKEN_NODE));
  }

  private static boolean isIhtml(Node node) {
    return node instanceof Element
        && ((Element) node).getTagName().startsWith(IHTML.PREFIX + ":");
  }

  private static Iterable<Element> allIhtml(Element root) {
    List<Element> all = new ArrayList<Element>();
    if (root.getTagName().startsWith(IHTML.PREFIX + ":")) { all.add(root); }
    for (Element el : Nodes.nodeListIterable(
             root.getElementsByTagName("*"), Element.class)) {
      if (el.getTagName().startsWith(IHTML.PREFIX + ":")) { all.add(el); }
    }
    return all;
  }

  private static FilePosition posOf(Attr a) {
    return FilePosition.span(
        Nodes.getFilePositionFor(a), Nodes.getFilePositionForValue(a));
  }
}
