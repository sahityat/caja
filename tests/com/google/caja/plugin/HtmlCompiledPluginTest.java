// Copyright 2007 Google Inc. All Rights Reserved
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

import com.google.caja.lexer.FilePosition;
import com.google.caja.lexer.CharProducer;
import com.google.caja.lexer.ExternalReference;
import com.google.caja.parser.AncestorChain;
import com.google.caja.parser.ParseTreeNode;
import com.google.caja.parser.quasiliteral.CajitaRewriter;
import com.google.caja.parser.html.Nodes;
import com.google.caja.parser.js.Block;
import com.google.caja.parser.js.CajoledModule;
import com.google.caja.parser.js.UncajoledModule;
import com.google.caja.reporting.MessageLevel;
import com.google.caja.reporting.MessageType;
import com.google.caja.reporting.TestBuildInfo;
import com.google.caja.util.RhinoTestBed;
import com.google.caja.util.TestUtil;
import com.google.caja.util.CajaTestCase;

import junit.framework.AssertionFailedError;

/**
 * End-to-end tests that compile a gadget to javascript and run the
 * javascript under Rhino to test them.
 *
 * @author stay@google.com (Mike Stay)
 */
public class HtmlCompiledPluginTest extends CajaTestCase {

  @Override
  protected void setUp() throws Exception {
    TestUtil.enableContentUrls();
    super.setUp();
  }

  @Override
  protected void tearDown() throws Exception {
    super.tearDown();
  }

  // TODO(metaweta): Move as many of these as possible to
  // CajitaRewriterTest using assertConsistent and the rest to
  // DebuggingSymbolsStageTest
  public final void testEmptyGadget() throws Exception {
    execGadget("", "", true);
  }

  public final void testTestingFramework() throws Exception {
    try {
      // Make sure our JSUnit failures escape the try blocks that allow
      // execution to continue into subsequent script blocks.
      execGadget("<script>fail('hiya');</script>", "", true);
    } catch (AssertionFailedError ex) {
      String message = ex.getMessage();
      String shortMessage = message.substring(
          message.indexOf(": ") + 2, message.indexOf("\n"));
      assertEquals("hiya", shortMessage);
      return;
    }
    fail("Expected failure");
  }

  public final void testVariableRefInHandlerFunction() throws Exception {
    execGadget(
        "  <script type='text/javascript'>"
        + "var foo;"
        + "</script>"
        + "<a onclick='foo + bar;'>foo</a>",
        "",

        true);
  }

  /**
   * Empty styles should not cause parse failure.
   * <a href="http://code.google.com/p/google-caja/issues/detail?id=56">bug</a>
   */
  public final void testEmptyStyle() throws Exception {
    execGadget("<style> </style>", "", true);
  }

  /**
   * Handlers should have their handlers rewritten.
   */
  public final void testHandlerRewriting() throws Exception {
    execGadget(
        "<a onclick=\"foo(this)\">hi</a>",

        // Handler is attached separately.
        ""
        + "assertEquals('<a target=\"_blank\">hi</a>',"
        + "             document.getElementById('test-test').innerHTML);",
        true);
  }

  public final void testECMAScript31Scoping() throws Exception {
    // TODO(stay): Once they decide on scoping & initialization rules, test
    // them here.
  }

  public final void testExceptionsInScriptBlocks() throws Exception {
    execGadget(
        "<script>var a = 0, b = 0;</script>" +
        "<script>throw new Error(); a = 1;</script>" +
        "<script>b = 1;</script>\n" +
        "<script>\n" +
        "  assertEquals(0, a);" +
        "  assertEquals(1, b);" +
        "</script>",

        "",

        true);
  }

  public final void testCajitaBlocks() throws Exception {
    execGadget(
        ""
        + "<script>"
        + "  'use strict';"
        + "  Object.prototype.hello = 'there';"  // Monkey patch
        + "</script>"
        + "<script>"
        + "  'use strict, cajita';"
        + "  assertEquals('not visible in cajita', undefined, ({}).hello);"
        + "</script>"
        + "<script>"
        + "  'use strict';"
        + "  assertEquals('visible in valija', 'there', ({}).hello);"
        + "</script>"
        + "<script>"
        + "  assertEquals("
        + "      'nested cajita fns not patched',"
        + "      'there,,',"
        + "      [ ({}).hello,"
        + "        (function () { 'use strict,cajita'; return {}.hello; })(),"
        + "        (function f() { 'use strict,cajita'; return {}.hello; })() ]"
        + "      .join(','));"
        + "</script>"
        + "<script>"
        + "  assertEquals('cajita fn decls hoisted to block', undefined, f);"
        + "  {"
        + "    function f() { 'use strict,cajita'; return {}.hello; }"
        + "    assertEquals('cajita fns not patchable', undefined, f());"
        + "    assertThrows(function () { f.foo = 'bar'; });"
        + "    assertEquals('cajita fns frozen', undefined, f.foo);"
        + "  }"
        + "</script>",

        // Not visible when uncajoled.
        "assertEquals(undefined, ({}).hello);",

        true);
  }

  public final void testCustomOnErrorHandler() throws Exception {
    // TODO(ihab.awad): onerror handling is broken. The exception handling code
    // generated by our HTML compiler does not work with Valija at the moment.
    execGadget(
        "<script>\n" +
        "  var a = 0, b = 0, messages = [];\n" +
        "  function onerror(message, source, lineNumber) {\n" +
        "    messages.push(source + ':' + lineNumber + ': ' + message);\n" +
        "  }\n" +
        "</script>\n" +
        "<script>throw new Error('panic'); a = 1;</script>\n" +        // line 7
        "<script>b = 1;</script>\n" +
        "<script>\n" +
        "  assertEquals(0, a);\n" +
        "  assertEquals(1, b);\n" +
        "  assertEquals(1, messages.length);\n" +
        "  assertEquals('testCustomOnErrorHandler:7: panic', messages[0]);\n" +
        "</script>",

        "",

        false);

  }

  public final void testPartialScript() throws Exception {
    PluginMeta meta = new PluginMeta();
    PluginCompiler compiler = new PluginCompiler(new TestBuildInfo(), meta, mq);
    compiler.setMessageContext(mc);
    Dom html = new Dom(htmlFragment(fromString("<script>{</script>")));
    compiler.addInput(AncestorChain.instance(html));

    boolean passed = compiler.run();
    assertFalse(passed);

    assertMessage(
        MessageType.END_OF_FILE, MessageLevel.ERROR,
        FilePosition.instance(is, 1, 9, 9, 1));
  }

  private void execGadget(String gadgetSpec, String tests, boolean valija)
      throws Exception {
    PluginMeta meta = new PluginMeta(new PluginEnvironment() {
      public CharProducer loadExternalResource(
          ExternalReference ref, String mimeType) {
        return null;
      }
      public String rewriteUri(ExternalReference uri, String mimeType) {
        return uri.toString();
      }
    });
    meta.setValijaMode(valija);
    PluginCompiler compiler = new PluginCompiler(new TestBuildInfo(), meta, mq);
    compiler.setMessageContext(mc);
    Dom html = new Dom(htmlFragment(fromString(gadgetSpec)));
    compiler.addInput(AncestorChain.instance(html));

    boolean failed = !compiler.run();

    if (failed) {
      fail();
    } else {
      CajoledModule jsTree = compiler.getJavascript();
      String staticHtml = Nodes.render(compiler.getStaticHtml(), true);
      String js = render(jsTree);

      Block valijaOrigNode = js(fromResource(
          "/com/google/caja/valija-cajita.js"));
      ParseTreeNode valijaCajoledNode =
          new CajitaRewriter(new TestBuildInfo(), mq, false)
              .expand(new UncajoledModule(valijaOrigNode));
      String valijaCajoled = render(valijaCajoledNode);

      String htmlStubUrl = TestUtil.makeContentUrl(
          "<html><head/><body><div id=\"test-test\">"
          + staticHtml
          + "</div></body></html>");

      try {
        RhinoTestBed.Input[] inputs = new RhinoTestBed.Input[] {
            // Browser Stubs
            new RhinoTestBed.Input(getClass(), "/js/jqueryjs/runtest/env.js"),
            // Console Stubs
            new RhinoTestBed.Input(getClass(), "console-stubs.js"),
            // Initialize the DOM
            new RhinoTestBed.Input(
                // Document not defined until window.location set.
                "location = '" + htmlStubUrl + "';\n",
                "dom"),
            // Make the assertTrue, etc. functions available to javascript
            new RhinoTestBed.Input(
                getClass(), "../../../../js/jsunit/2.2/jsUnitCore.js"),
            // Plugin Framework
            new RhinoTestBed.Input(
                getClass(), "../../../../js/json_sans_eval/json_sans_eval.js"),
            new RhinoTestBed.Input(getClass(), "../cajita.js"),
            new RhinoTestBed.Input(
                "___.setLogFunc(function(s, opt_stop) { console.log(s); });",
                "setLogFunc-setup"),
            new RhinoTestBed.Input(
                "var valijaMaker = {};\n" +
                "var testImports = ___.copy(___.sharedImports);\n" +
                "testImports.loader = {\n" +
                "  provide: ___.markFuncFreeze(\n" +
                "      function(v) { valijaMaker = v; })\n" +
                "};\n" +
                "testImports.outers = ___.copy(___.sharedImports);\n" +
                "___.getNewModuleHandler().setImports(testImports);",
                getName() + "valija-setup"),
            new RhinoTestBed.Input(
                valijaCajoled,
                "valija-cajoled"),
            new RhinoTestBed.Input(getClass(), "bridal.js"),
            new RhinoTestBed.Input(getClass(), "html-emitter.js"),
            new RhinoTestBed.Input(getClass(), "container.js"),
            // The gadget
            new RhinoTestBed.Input(js.toString(), "gadget"),
            // The tests
            new RhinoTestBed.Input(tests, "tests"),
          };
        RhinoTestBed.runJs(inputs);
      } catch (Exception e) {
        System.out.println("Compiled gadget: \n" + staticHtml + "\n" + js);
        throw e;
      } catch (Error e) {
        System.out.println("Compiled gadget: \n" + staticHtml + "\n" + js);
        throw e;
      }
    }
  }
}
