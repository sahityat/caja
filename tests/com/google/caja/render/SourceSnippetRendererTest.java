// Copyright (C) 2008 Google Inc.
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

package com.google.caja.render;

import com.google.caja.lexer.TokenConsumer;
import com.google.caja.lexer.InputSource;
import com.google.caja.reporting.MessageContext;
import com.google.caja.util.Callback;

import java.util.Map;
import java.io.IOException;

/**
 * @author ihab.awad@gmail.com
 */
public class SourceSnippetRendererTest extends OrigSourceRendererTestCase {
  public final void testRendering() throws Exception {
    runTest(
        "ss-golden.js", "ss-rewritten-tokens.txt",
        "ss-test-input.js");
  }

  @Override
  protected TokenConsumer createRenderer(
      Map<InputSource, ? extends CharSequence> originalSource,
      MessageContext mc, Appendable out, Callback<IOException> exHandler) {
    return new SourceSnippetRenderer(originalSource, mc, out, exHandler);
  }
}
