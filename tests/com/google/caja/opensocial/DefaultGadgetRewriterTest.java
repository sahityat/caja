// Copyright (C) 2007 Google Inc.
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
import com.google.caja.lexer.ExternalReference;
import com.google.caja.lexer.InputSource;
import com.google.caja.lexer.TokenConsumer;
import com.google.caja.reporting.EchoingMessageQueue;
import com.google.caja.reporting.Message;
import com.google.caja.reporting.MessageContext;
import com.google.caja.reporting.MessageLevel;
import com.google.caja.reporting.RenderContext;
import com.google.caja.reporting.TestBuildInfo;
import com.google.caja.util.CajaTestCase;
import com.google.caja.util.FailureIsAnOption;
import com.google.caja.util.TestUtil;

import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/**
 * @author ihab.awad@gmail.com (Ihab Awad)
 */
public class DefaultGadgetRewriterTest extends CajaTestCase {

  private static final UriCallback uriCallback = new UriCallback() {
    public Reader retrieve(ExternalReference extref, String mimeType)
        throws UriCallbackException {
      if ("file".equals(extref.getUri().getScheme())) {
        try {
          return new FileReader(extref.getUri().getPath());
        } catch (FileNotFoundException ex) {
          throw new UriCallbackException(extref, ex);
        }
      }
      throw new UriCallbackException(extref);
    }

    public URI rewrite(ExternalReference extref, String mimeType) {
      try {
        return URI.create(
            "http://url-proxy.test.google.com/"
            + "?url=" + URLEncoder.encode(extref.getUri().toString(), "UTF-8")
            + "&mime-type=" + URLEncoder.encode(mimeType, "UTF-8"));
      } catch (UnsupportedEncodingException ex) {
        // If we don't support UTF-8 we're in trouble
        throw new RuntimeException(ex);
      }
    }
  };

  private DefaultGadgetRewriter rewriter;

  @Override
  public void setUp() throws Exception {
    super.setUp();
    rewriter = new DefaultGadgetRewriter(
        new TestBuildInfo(),
        new EchoingMessageQueue(new PrintWriter(System.err), mc, false)) {
          @Override
          protected RenderContext createRenderContext(TokenConsumer out) {
            return new RenderContext(out);
          }
        };
  }

  @Override
  public void tearDown() throws Exception {
    super.tearDown();
    rewriter = null;
  }

  // Test Gadget parsing
  public final void testInlineGadget() throws Exception {
    assertRewritePasses("listfriends-inline.xml", MessageLevel.WARNING);
  }

  public final void testValijaGadget() throws Exception {
    rewriter.setValijaMode(true);
    assertRewritePasses("SocialHelloWorld.xml", MessageLevel.WARNING);
    rewriter.setValijaMode(false);
  }

  public final void testSocialHelloWorld() throws Exception {
    assertRewritePasses("SocialHelloWorld.xml", MessageLevel.WARNING);
  }

  public final void testParsing() throws Exception {
    assertRewritePasses("test-parsing.xml", MessageLevel.WARNING);
  }

  public final void testSourcedGadget() throws Exception {
    assertRewritePasses("listfriends.xml", MessageLevel.WARNING);
  }

  // Test Gadget rewriting
  public final void testExampleGadget() throws Exception {
    assertRewriteMatches("example.xml", "example-rewritten.xml",
                         MessageLevel.ERROR);
  }

  // Check that the validating and rewriting passes are hooked up.
  public final void testTargetsDisallowed() throws Exception {
    assertRewritesWithMessage(
        "<a target=\"_top\">Redirect window</a>",
        "attribute target cannot have value _top",
    MessageLevel.WARNING, false /* should not fail */);
  }

  public final void testMetaRefreshDisallowed() throws Exception {
    assertRewritesWithMessage(
        "<meta http-equiv=\"refresh\" content=\"5;http://foo.com\"/>",
        "removing disallowed tag meta",
        MessageLevel.WARNING, false /* should not fail */);
  }

  public final void testStylesSanitized() throws Exception {
    assertRewritesWithMessage(
        "<p style=\"color: expression(foo)\">Bar</p>",
        "removing css property color with bad value: ==>expression(foo)<==",
        MessageLevel.WARNING, false /* should not fail */);
  }

  @FailureIsAnOption
  public final void testStylesInScript() throws Exception {
    // CSS template expansion works on style templates in extracted event
    // handlers and script tags.
    // TODO(ihab.awad): Rewrite "golden" or turn into a functional test
    assertRewriteMatches("example-dynamic-styles.xml",
                         "example-dynamic-styles-rewritten.xml",
                         MessageLevel.WARNING);
  }

  private void assertRewritePasses(String file, MessageLevel failLevel)
      throws Exception {
    URI gadgetUri = TestUtil.getResource(getClass(), file);
    rewriter.rewrite(gadgetUri, fromResource(file), uriCallback, "canvas",
                     System.out);
    checkMessages(failLevel);
  }

  private void assertRewriteMatches(
      String file, String goldenFile, MessageLevel failLevel)
      throws Exception {
    URI gadgetUri = TestUtil.getResource(getClass(), file);
    CharProducer cp = fromResource(file);

    StringBuilder sb = new StringBuilder();
    rewriter.rewrite(gadgetUri, cp, uriCallback, "canvas", sb);
    String actual = normalXml(sb.toString()).trim();

    checkMessages(failLevel);

    String expected = normalXml(TestUtil.readResource(getClass(), goldenFile))
        .trim();
    if (!expected.equals(actual)) {
      assertEquals(actual,
                   normalizeIndentation(expected),
                   normalizeIndentation(actual));
    }
  }

  private static String normalXml(String xml) {
    return xml.replaceFirst("^<\\?xml[^>]*>", "");
  }

  private void assertRewritesWithMessage(String htmlContent, String msg,
      MessageLevel level, boolean rewriteShouldFail)
      throws Exception {
    String input = (
        "<?xml version=\"1.0\"?>"
        + "<Module>"
        + "<ModulePrefs title=\"Example Gadget\">"
        + "<Require feature=\"opensocial-0.5\"/>"
        + "</ModulePrefs>"
        + "<Content type=\"html\">"
        + "<![CDATA[" + htmlContent + "]]>"
        + "</Content>"
        + "</Module>");
    URI gadgetUri = URI.create("http://unittest.google.com/foo/bar/");
    CharProducer cp = fromString(input, new InputSource(gadgetUri));

    try {
      rewriter.rewrite(gadgetUri, cp, uriCallback, "canvas", System.out);
      if (rewriteShouldFail)
        fail("rewrite should have failed with message " + msg);
    } catch (GadgetRewriteException ex) {
      // pass
    }

    List<Message> errors = getMessagesExceedingLevel(level);

    assertFalse("Expected error msg: " + msg, errors.isEmpty());
    String actualMsg = errors.get(0).format(new MessageContext());
    // strip off the file position since that's tested in the modules that
    // generate the errors.
    actualMsg = actualMsg.substring(actualMsg.indexOf(": ") + 2).trim();
    assertEquals(msg, actualMsg);
  }

  private List<Message> getMessagesExceedingLevel(MessageLevel limit) {
    List<Message> matches = new ArrayList<Message>();
    for (Message msg : rewriter.getMessageQueue().getMessages()) {
      if (msg.getMessageLevel().compareTo(limit) >= 0) {
        matches.add(msg);
      }
    }
    return matches;
  }

  private void checkMessages(MessageLevel failLevel) {
    List<Message> failures = getMessagesExceedingLevel(failLevel);
    MessageContext mc = new MessageContext();
    if (!failures.isEmpty()) {
      StringBuilder sb = new StringBuilder();
      for (Message failure : failures) {
        sb.append(failure.getMessageLevel())
            .append(" : ")
            .append(failure.format(mc))
            .append('\n');
      }
      fail(sb.toString().trim());
    }
  }

  private static final String normalizeIndentation(String xml) {
    return xml.replaceAll("\n +([?:.])", "$1")
        .replaceAll("\\(\n +", "(")
        .replaceAll("\n *", " ");
  }
}
