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
import com.google.caja.parser.html.DomParser;
import com.google.caja.parser.html.Nodes;
import com.google.caja.reporting.Message;
import com.google.caja.reporting.MessageLevel;
import com.google.caja.reporting.MessagePart;
import com.google.caja.util.CajaTestCase;
import com.google.caja.util.Name;

import java.io.StringReader;

import org.w3c.dom.Element;

public class IhtmlSanityCheckerTest extends CajaTestCase {

  public final void testEmptyTemplate() throws Exception {
    runTest(
        "<ihtml:template formals=\"\" name=\"hi\" />",
        "<ihtml:template formals='' name='hi'/>");
  }
  public final void testHtmlInTemplate() throws Exception {
    runTest(
        "<ihtml:template formals=\"\" name=\"hi\"><p>Hi</p></ihtml:template>",
        "<ihtml:template formals=\"\" name=\"hi\"><p>Hi</p></ihtml:template>");
  }
  public final void testSimpleMessage() throws Exception {
    runTest(
        ""
        + "<ihtml:template formals=\"a\" name=\"t\">"
        + "<ihtml:message name=\"hi\">Hello</ihtml:message>"
        + "</ihtml:template>",
        ""
        + "<ihtml:template formals='a' name='t'>"
        + "<ihtml:message name='hi'>Hello</ihtml:message>"
        + "</ihtml:template>");
  }
  public final void testMultipleFormals() throws Exception {
    runTest(
        ""
        + "<ihtml:template formals=\"a b\" name=\"t\">"
        + "<ihtml:message name=\"hi\">Hello</ihtml:message>"
        + "</ihtml:template>",
        ""
        + "<ihtml:template formals='a b' name='t'>"
        + "<ihtml:message name='hi'>Hello</ihtml:message>"
        + "</ihtml:template>");
  }
  public final void testMultipleVars() throws Exception {
    runTest("<ihtml:do vars=\"a _b\" />", "<ihtml:do vars=\"a _b\" />");
  }
  public final void testUnnamedMessage() throws Exception {
    runTest(
        "<ihtml:template formals=\"a b\" name=\"t\" />",
        ""
        + "<ihtml:template formals='a b' name='t'>"
        + "<ihtml:message>Hello</ihtml:message>"
        + "</ihtml:template>",
        new Message(IhtmlMessageType.MISSING_ATTRIB,
                    Name.xml("ihtml:message"), Name.xml("name"),
                    FilePosition.instance(is, 1, 40, 40, 36))
        );
  }
  public final void testMisnamedMessage() throws Exception {
    runTest(
        "<ihtml:template formals=\"a b\" name=\"t\" />",
        ""
        + "<ihtml:template formals=\"a b\" name=\"t\">"
        + "<ihtml:message name=\"x__\">"
        + "Hello"
        + "</ihtml:message>"
        + "</ihtml:template>",
        new Message(IhtmlMessageType.BAD_ATTRIB,
                    FilePosition.instance(is, 1, 55, 55, 10),
                    Name.xml("ihtml:message"),
                    Name.xml("name"),
                    MessagePart.Factory.valueOf("x__"))
        );
  }
  public final void testMessageWithPlaceholder() throws Exception {
    runTest(
        ""
        + "<ihtml:template formals=\"x\" name=\"t\">"
        + "<ihtml:message name=\"hi\">"
        + "Hello "
        + "<ihtml:ph name=\"planet\" />"
        + "<ihtml:dynamic expr=\"planet\" />"
        + "<ihtml:eph />!"
        + "</ihtml:message>"
        + "</ihtml:template>",
        ""
        + "<ihtml:template formals=\"x\" name=\"t\">"
        + "<ihtml:message name=\"hi\">"
        + "Hello "
        + "<ihtml:ph name=\"planet\"/>"
        + "<ihtml:dynamic expr=\"planet\"/>"
        + "<ihtml:eph/>!"
        + "</ihtml:message>"
        + "</ihtml:template>");
  }
  public final void testBadPlaceholderName() throws Exception {
    runTest(
        ""
        + "<ihtml:template formals=\"\" name=\"t\">"
        + "<ihtml:message name=\"hi\">"
        + "Hello "
        + "<ihtml:dynamic expr=\"planet\" />"
        + "!"
        + "</ihtml:message>"
        + "</ihtml:template>",
        ""
        + "<ihtml:template formals=\"\" name=\"t\">"
        + "<ihtml:message name=\"hi\">"
        + "Hello "
        + "<ihtml:ph name=\"if\"/>"
        + "<ihtml:dynamic expr=\"planet\"/>"
        + "<ihtml:eph/>!"
        + "</ihtml:message>"
        + "</ihtml:template>",
        new Message(
            IhtmlMessageType.BAD_ATTRIB,
            FilePosition.instance(is, 1, 78, 78, 9),
            Name.xml("ihtml:ph"),
            Name.xml("name"),
            MessagePart.Factory.valueOf("if")),
        new Message(
            IhtmlMessageType.ORPHANED_PLACEHOLDER_END,
            FilePosition.instance(is, 1, 119, 119, 12)));
  }
  public final void testUnnamedPlaceholder() throws Exception {
    runTest(
        ""
        + "<ihtml:template formals=\"\" name=\"t\">"
        + "<ihtml:message name=\"hi\">"
        + "Hello "
        + "<ihtml:dynamic expr=\"planet\" />"
        + "!"
        + "</ihtml:message>"
        + "</ihtml:template>",
        ""
        + "<ihtml:template formals=\"\" name=\"t\">"
        + "<ihtml:message name=\"hi\">"
        + "Hello "
        + "<ihtml:ph/>"
        + "<ihtml:dynamic expr=\"planet\"/>"
        + "<ihtml:eph/>!"
        + "</ihtml:message>"
        + "</ihtml:template>",
        new Message(
            IhtmlMessageType.MISSING_ATTRIB,
            Name.xml("ihtml:ph"),
            Name.xml("name"),
            FilePosition.instance(is, 1, 68, 68, 11)),
        new Message(
            IhtmlMessageType.ORPHANED_PLACEHOLDER_END,
            FilePosition.instance(is, 1, 109, 109, 12)));
  }
  public final void testNestedMessage() throws Exception {
    runTest(
        ""
        + "<ihtml:template formals=\"\" name=\"t\">"
        + "<ihtml:message name=\"hi\">"
        + "Hello "
        + "<ihtml:ph name=\"planet\" />"
        + "<ihtml:dynamic expr=\"planet\" />"
        + "<ihtml:eph />!"
        + "</ihtml:message>"
        + "</ihtml:template>",
        ""
        + "<ihtml:template formals=\"\" name=\"t\">"
        + "<ihtml:message name=\"hi\">"
        + "<ihtml:message name=\"there\" />"
        + "Hello "
        + "<ihtml:ph name=\"planet\"/>"
        + "<ihtml:dynamic expr=\"planet\"/>"
        + "<ihtml:eph/>!"
        + "</ihtml:message>"
        + "</ihtml:template>",
        new Message(
            IhtmlMessageType.NESTED_MESSAGE,
            FilePosition.instance(is, 1, 62, 62, 30),
            FilePosition.instance(is, 1, 37, 37, 145)));
  }
  public final void testUnclosedPlaceholder() throws Exception {
    runTest(
        ""
        + "<ihtml:template formals=\"\" name=\"t\">"
        + "<ihtml:message name=\"hi\">"
        + "Hello "
        + "<ihtml:dynamic expr=\"planet\" />!"
        + "</ihtml:message>"
        + "</ihtml:template>",
        ""
        + "<ihtml:template formals=\"\" name=\"t\">"
        + "<ihtml:message name=\"hi\">"
        + "Hello "
        + "<ihtml:ph name=\"planet\"/>"
        + "<ihtml:dynamic expr=\"planet\"/>!"
        + "</ihtml:message>"
        + "</ihtml:template>",
        new Message(
            IhtmlMessageType.UNCLOSED_PLACEHOLDER,
            FilePosition.instance(is, 1, 68, 68, 56)));
  }
  public final void testUnclosedPlaceholder2() throws Exception {
    runTest(
        ""
        + "<ihtml:template formals=\"\" name=\"t\">"
        + "<ihtml:message name=\"hi\">"
        + "Hello "
        + "<ihtml:dynamic expr=\"planet\" />"
        + "<ihtml:ph name=\"punc\" />"
        + "!"
        + "<ihtml:eph />"
        + "</ihtml:message>"
        + "</ihtml:template>",
        ""
        + "<ihtml:template formals=\"\" name=\"t\">"
        + "<ihtml:message name=\"hi\">"
        + "Hello "
        + "<ihtml:ph name=\"planet\"/>"
        + "<ihtml:dynamic expr=\"planet\"/>"
        + "<ihtml:ph name=\"punc\"/>"
        + "!"
        + "<ihtml:eph/>"
        + "</ihtml:message>"
        + "</ihtml:template>",
        new Message(
            IhtmlMessageType.UNCLOSED_PLACEHOLDER,
            // Ends before placeholder punc.
            FilePosition.instance(is, 1, 68, 68, 55)));
  }
  public final void testOrphanedPlaceholder() throws Exception {
    runTest(
        ""
        + "<ihtml:template formals=\"\" name=\"t\">"
        + "Hello "
        + "<ihtml:dynamic expr=\"planet\" />!"
        + "</ihtml:template>",
        ""
        + "<ihtml:template formals=\"\" name=\"t\">"
        + "Hello "
        + "<ihtml:ph name=\"planet\"/>"
        + "<ihtml:dynamic expr=\"planet\"/>!"
        + "<ihtml:eph/>"
        + "</ihtml:template>",
        new Message(
            IhtmlMessageType.ORPHANED_PLACEHOLDER,
            FilePosition.instance(is, 1, 43, 43, 25)),
        new Message(
            IhtmlMessageType.ORPHANED_PLACEHOLDER,
            FilePosition.instance(is, 1, 99, 99, 12)));
  }
  public final void testOrphanedPlaceholderEnd() throws Exception {
    runTest(
        ""
        + "<ihtml:template formals=\"\" name=\"t\">"
        + "Hello "
        + "<ihtml:dynamic expr=\"planet\" />!"
        + "</ihtml:template>",
        ""
        + "<ihtml:template formals=\"\" name=\"t\">"
        + "Hello "
        + "<ihtml:dynamic expr=\"planet\"/>!"
        + "<ihtml:eph/>"
        + "</ihtml:template>",
        new Message(
            IhtmlMessageType.ORPHANED_PLACEHOLDER,
            FilePosition.instance(is, 1, 74, 74, 12)));
  }
  public final void testIhtmlElementInMessageOutsidePlaceholder()
      throws Exception {
    runTest(
        ""
        + "<ihtml:template formals=\"\" name=\"t\" />",
        ""
        + "<ihtml:template formals=\"\" name=\"t\">"
        + "<ihtml:message name='SayHowdy'>"
        + "Hello "
        + "<ihtml:dynamic expr=\"planet\"/>!"
        + "</ihtml:message>"
        + "</ihtml:template>",
        new Message(
            IhtmlMessageType.IHTML_IN_MESSAGE_OUTSIDE_PLACEHOLDER,
            FilePosition.instance(is, 1, 74, 74, 30),
            Name.xml("ihtml:dynamic")));
  }
  public final void testTemplateNames() throws Exception {
    runTest(
        "<ihtml:template formals=\"x\" name=\"hi\" />",
        ""
        + "<ihtml:template formals='x' name='hi'>"
        + "<ihtml:template name='3nested' formals='a,,x,if,3' zoinks='ahoy'/>"
        + "</ihtml:template>",
        new Message(
            IhtmlMessageType.BAD_ATTRIB,
            FilePosition.instance(is, 1, 55, 55, 14),
            Name.xml("ihtml:template"),
            Name.xml("name"),
            MessagePart.Factory.valueOf("3nested")),
        new Message(
            IhtmlMessageType.BAD_ATTRIB,
            FilePosition.instance(is, 1, 70, 70, 19),
            Name.xml("ihtml:template"),
            Name.xml("formals"),
            MessagePart.Factory.valueOf("a,,x,if,3")),
        new Message(
            IhtmlMessageType.BAD_ATTRIB,
            FilePosition.instance(is, 1, 90, 90, 13),
            Name.xml("ihtml:template"),
            Name.xml("zoinks"),
            MessagePart.Factory.valueOf("ahoy")));
  }
  public final void testCalls() throws Exception {
    runTest(
        ""
        + "<ihtml:template formals=\"\" name=\"hi\">\n"
        + "  <ihtml:call baz=\"boo\" foo=\"bar\" ihtml:template=\"bye\" />\n"
        + "</ihtml:template>",
        ""
        + "<ihtml:template name='hi' formals=''>\n"
        + "  <ihtml:call ihtml:template='bye' foo='bar' baz='boo'/>\n"
        + "</ihtml:template>");
  }
  public final void testBadCall() throws Exception {
    runTest(
        ""
        + "<ihtml:template formals=\"\" name=\"hi\">\n"
        + "  \n"
        + "</ihtml:template>",
        ""
        + "<ihtml:template name='hi' formals=''>\n"
        + "  <ihtml:call foo='bar' baz:boo='far'/>\n"
        + "</ihtml:template>",
        new Message(
            IhtmlMessageType.MISSING_ATTRIB,
            FilePosition.instance(is, 2, 41, 3, 37),
            Name.xml("ihtml:call"),
            Name.xml("ihtml:template")),
        new Message(
            IhtmlMessageType.BAD_ATTRIB,
            FilePosition.instance(is, 2, 63, 25, 13),
            Name.xml("ihtml:call"),
            Name.xml("baz:boo"),
            MessagePart.Factory.valueOf("far"))
        );
  }
  public final void testMisplacedPlaceholderContent() throws Exception {
    runTest(
        ""
        + "<ihtml:template formals=\"\" name=\"t\">\n"
        + "  <ihtml:message name=\"m\">\n"
        + "    \n"
        + "    \n"
        + "  </ihtml:message>\n"
        + "</ihtml:template>",
        ""
        + "<ihtml:template formals=\"\" name=\"t\">\n"
        + "  <ihtml:message name=\"m\">\n"
        + "    <ihtml:ph name=\"p\">Hi</ihtml:ph>\n"
        + "    <ihtml:eph>There</ihtml:eph>\n"
        + "  </ihtml:message>\n"
        + "</ihtml:template>",
        new Message(
            IhtmlMessageType.INAPPROPRIATE_CONTENT,
            FilePosition.instance(is, 3, 88, 24, 2),
            MessagePart.Factory.valueOf("ihtml:ph")),
        new Message(
            IhtmlMessageType.INAPPROPRIATE_CONTENT,
            FilePosition.instance(is, 4, 117, 16, 5),
            MessagePart.Factory.valueOf("ihtml:eph")));
  }
  public final void testBadAttr() throws Exception {
    runTest(
        ""
        + "<ihtml:template formals=\"\" name=\"t\">\n"
        + "  \n"
        + "  <div><ihtml:element>p</ihtml:element></div>\n"
        + "</ihtml:template>",
        ""
        + "<ihtml:template formals=\"\" name=\"t\">\n"
        + "  <ihtml:element bogus=\"\">p</ihtml:element>\n"
        + "  <div><ihtml:element>p</ihtml:element></div>\n"
        + "</ihtml:template>",
        new Message(
            IhtmlMessageType.BAD_ATTRIB,
            FilePosition.instance(is, 2, 55, 18, 8),
            Name.xml("ihtml:element"),
            Name.xml("bogus"),
            MessagePart.Factory.valueOf("")));
  }
  public final void testDynamicAttr() throws Exception {
    runTest(
        ""
        + "<ihtml:template formals=\"\" name=\"t\">\n"
        + "  <a>\n"
        + "    <ihtml:attribute name=\"href\"><ihtml:dynamic expr=\"url\" />"
        + "</ihtml:attribute>\n"
        + "    <ihtml:attribute name=\"title\">\n"
        + "      <ihtml:message name=\"linkHover\">Howdy</ihtml:message>\n"
        + "    </ihtml:attribute>\n"
        + "    Link Text\n"
        + "    \n"
        + "  </a>\n"
        + "</ihtml:template>",
        ""
        + "<ihtml:template formals=\"\" name=\"t\">\n"
        + "  <a>\n"
        + "    <ihtml:attribute name=\"href\"><ihtml:dynamic expr=\"url\""
        + "     /></ihtml:attribute>\n"
        + "    <ihtml:attribute name=\"title\">\n"
        + "      <ihtml:message name=\"linkHover\">Howdy</ihtml:message>\n"
        + "    </ihtml:attribute>\n"
        + "    Link Text\n"
        + "    <ihtml:attribute>onclick=\"badness()\"</ihtml:attribute>\n"
        + "  </a>\n"
        + "</ihtml:template>",
        new Message(
            IhtmlMessageType.MISSING_ATTRIB,
            FilePosition.instance(is, 8, 264, 5, 54),
            Name.xml("ihtml:attribute"),
            Name.xml("name")));
  }
  public final void testMisplacedElementAndAttribute() throws Exception {
    runTest(
        ""
        + "<ihtml:template formals=\"\" name=\"t\">\n"
        + "  <ihtml:element />\n"
        + "  <ihtml:message name=\"m\">\n"
        + "    \n"
        + "    <ihtml:ph name=\"ph\" />\n"
        + "      \n"
        + "    <ihtml:eph />\n"
        + "  </ihtml:message>\n"
        + "  <ihtml:do init=\"maybe\">\n"
        + "    <ihtml:element />\n"
        + "  <ihtml:else />\n"
        + "    <ihtml:element />\n"
        + "  </ihtml:do>\n"
        + "</ihtml:template>",
        ""
        + "<ihtml:template formals=\"\" name=\"t\">\n"
        + "  <ihtml:element/>\n"  // OK inside templates
        + "  <ihtml:message name=\"m\">\n"
        + "    <ihtml:element/>\n"  // But not inside messages
        + "    <ihtml:ph name=\"ph\"/>\n"
        + "      <ihtml:element/>\n"  // Not even inside messages
        + "    <ihtml:eph/>\n"
        + "  </ihtml:message>\n"
        + "  <ihtml:do init=\"maybe\">\n"
        + "    <ihtml:element/>\n"  // OK inside a conditional
        + "  <ihtml:else/>\n"
        + "    <ihtml:element/>\n"  // OK inside a conditional's alternate
        + "  </ihtml:do>\n"
        + "</ihtml:template>",
        new Message(
            IhtmlMessageType.MISPLACED_ELEMENT,
            FilePosition.instance(is, 4, 88, 5, 16),
            Name.xml("ihtml:element"),
            Name.xml("ihtml:message")),
        new Message(
            IhtmlMessageType.MISPLACED_ELEMENT,
            FilePosition.instance(is, 6, 137, 7, 16),
            Name.xml("ihtml:element"),
            Name.xml("ihtml:message")));
  }
  public final void testContentType() throws Exception {
    // The callingContext attribute is set by a later pass, so make sure it
    // can't be passed in.
    runTest(
        ""
        + "<ihtml:template formals=\"\" name=\"main\">\n"
        + "  \n"
        + "</ihtml:template>",
        ""
        + "<ihtml:template formals=\"\" name=\"main\">\n"
        + "  <ihtml:template formals=\"\" name=\"sub\"\n"
        + "   " + IHTML.CALLING_CONTEXT_ATTR + "=\"div\">\n"
        + "  </ihtml:template>\n"
        + "</ihtml:template>",
        new Message(
            IhtmlMessageType.BAD_ATTRIB,
            FilePosition.instance(is, 3, 4, 4, 20),
            Name.xml("ihtml:template"),
            Name.xml("callingContext"),
            MessagePart.Factory.valueOf("div")));
  }

  private void runTest(
      String goldenIhtml, String inputIhtml, Message... expectedMessages)
      throws Exception {
    Element ihtmlRoot = new DomParser(
        DomParser.makeTokenQueue(
            FilePosition.startOfFile(is), new StringReader(inputIhtml), true),
        true, mq)
        .parseDocument();
    new IhtmlSanityChecker(mq).check(ihtmlRoot);

    for (Message msg : expectedMessages) {
      assertMessage(true, msg.getMessageType(), msg.getMessageLevel(),
                    msg.getMessageParts().toArray(new MessagePart[0]));
    }
    assertMessagesLessSevereThan(MessageLevel.WARNING);

    String checkedIhtml = Nodes.render(ihtmlRoot, true);
    assertEquals(goldenIhtml, checkedIhtml);
  }
}
