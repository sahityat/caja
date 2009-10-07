// Copyright 2008 Google Inc. All Rights Reserved.
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

package com.google.caja.service;

import com.google.caja.lexer.CharProducer;
import com.google.caja.lexer.InputSource;
import com.google.caja.lexer.JsLexer;
import com.google.caja.lexer.JsTokenQueue;
import com.google.caja.lexer.ParseException;
import com.google.caja.parser.js.Block;
import com.google.caja.parser.js.Parser;
import com.google.caja.parser.js.UncajoledModule;
import com.google.caja.parser.quasiliteral.CajitaRewriter;
import com.google.caja.parser.quasiliteral.DefaultValijaRewriter;
import com.google.caja.parser.quasiliteral.Rewriter;
import com.google.caja.reporting.MessageQueue;
import com.google.caja.reporting.SimpleMessageQueue;
import com.google.caja.reporting.BuildInfo;
import com.google.caja.util.Pair;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.StringReader;
import java.net.URI;

/**
 * Retrieves javascript files and cajoles them
 *
 * @author jasvir@google.com (Jasvir Nagra)
 */
public class JsHandler implements ContentHandler {
  private final BuildInfo buildInfo;

  public JsHandler(BuildInfo buildInfo) {
    this.buildInfo = buildInfo;
  }

  public boolean canHandle(URI uri, CajolingService.Transform transform,
      String inputContentType, String outputContentType,
      ContentTypeCheck checker) {
    return checker.check("text/javascript", inputContentType)
        && checker.check(outputContentType, "text/javascript")
        && (transform == null
            || transform.equals(CajolingService.Transform.CAJITA)
            || transform.equals(CajolingService.Transform.VALIJA));
  }

  public Pair<String,String> apply(URI uri, CajolingService.Transform transform,
      String inputContentType, String outputContentType, String charset,
      byte[] content, OutputStream response)
      throws UnsupportedContentTypeException {
    if (charset == null) { charset = "UTF-8"; }
    try {
      OutputStreamWriter writer = new OutputStreamWriter(response, "UTF-8");
      boolean valijaMode = CajolingService.Transform.VALIJA.equals(transform);
      cajoleJs(uri, new StringReader(new String(content, charset)), valijaMode,
          writer);
      writer.flush();
    } catch (IOException e) {
      throw new UnsupportedContentTypeException();
    }
    return new Pair<String, String>("text/javascript", "UTF-8");
  }

  private void cajoleJs(URI inputUri, Reader cajaInput, boolean valijaMode,
      Appendable output)
      throws IOException, UnsupportedContentTypeException {
    InputSource is = new InputSource (inputUri);
    CharProducer cp = CharProducer.Factory.create(cajaInput,is);
    MessageQueue mq = new SimpleMessageQueue();
    try {
      JsTokenQueue tq = new JsTokenQueue(new JsLexer(cp), is);
      Block input = new Parser(tq, mq).parse();
      tq.expectEmpty();

      Rewriter vrw = new DefaultValijaRewriter(mq, false /* logging */);
      Rewriter crw = new CajitaRewriter(buildInfo, mq, false /* logging */);
      UncajoledModule ucm = new UncajoledModule(input);
      if (valijaMode) {
        output.append(Rewriter.render(crw.expand(vrw.expand(ucm))));
      } else {
        output.append(Rewriter.render(crw.expand(ucm)));
      }
    } catch (ParseException e) {
      throw new UnsupportedContentTypeException();
    } catch (IllegalArgumentException e) {
      throw new UnsupportedContentTypeException();
    } catch (IOException e) {
      throw new UnsupportedContentTypeException();
    }
  }
}
