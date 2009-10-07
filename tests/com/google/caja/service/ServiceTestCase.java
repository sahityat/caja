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

package com.google.caja.service;

import com.google.caja.util.CajaTestCase;
import com.google.caja.reporting.TestBuildInfo;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;

/**
 * @author jasvir@google.com (Jasvir Nagra)
 */
public abstract class ServiceTestCase extends CajaTestCase {
  private CajolingService service;
  private Map<URI, FetchedData> uriContent;

  @Override
  protected void setUp() throws Exception {
    super.setUp();

    service = new CajolingService(new TestBuildInfo()) {
      @Override
      protected FetchedData fetch(URI uri) throws IOException {
        if (!uriContent.containsKey(uri)) {
          throw new IOException(uri.toString());
        }
        return uriContent.get(uri);
      }
    };
    uriContent = new HashMap<URI, FetchedData>();
  }

  @Override
  protected void tearDown() throws Exception {
    super.tearDown();
  }

  protected void registerUri(String uri, String content, String contentType) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try {
      Writer w = new OutputStreamWriter(out, "UTF-8");
      w.write(content);
      w.flush();
    } catch (UnsupportedEncodingException ex) {
      throw new RuntimeException(ex);
    } catch (IOException ex) {
      throw new RuntimeException(ex);
    }
    registerUri(uri, out.toByteArray(), contentType, "UTF-8");
  }

  protected void registerUri(
      String uri, byte[] content, String contentType, String charset) {
    uriContent.put(
        URI.create(uri),
        new FetchedData(content, contentType, charset));
  }

  protected Object requestGet(String queryString) throws Exception {
    TestHttpServletRequest req = new TestHttpServletRequest(queryString);
    TestHttpServletResponse resp = new TestHttpServletResponse();
    service.doGet(req, resp);
    return resp.getOutputObject();
  }

  protected Object requestPost(
      String queryString,
      byte[] content,
      String contentType,
      String contentEncoding) throws Exception {
    TestHttpServletRequest req =
        new TestHttpServletRequest(queryString, content, contentType,
            contentEncoding);
    TestHttpServletResponse resp = new TestHttpServletResponse();
    service.doPost(req, resp);
    return resp.getOutputObject();
  }
  
  protected static String valijaModule(String... lines) {
    String prefix = (
        ""
        + "{\n"
        + "  ___.loadModule({\n"
        + "      'instantiate': function (___, IMPORTS___) {\n"
        + "        var moduleResult___ = ___.NO_RESULT;\n"
        + "        var $v = ___.readImport(IMPORTS___, '$v', {\n"
        + "            'getOuters': {\n"
        + "              '()': { }\n"
        + "            },\n"
        + "            'initOuter': {\n"
        + "              '()': { }\n"
        + "            },\n"
        + "            'cf': {\n"
        + "              '()': { }\n"
        + "            },\n"
        + "            'ro': {\n"
        + "              '()': { }\n"
        + "            }\n"
        + "          });\n"
        + "        var $dis = $v.getOuters();\n"
        + "        $v.initOuter('onerror');\n"
        );
    String suffix = (
        ""
        + "        return moduleResult___;\n"
        + "      },\n"
        + "      'includedModules': [ ],\n"
        + "      'cajolerName': 'com.google.caja',\n"
        + "      'cajolerVersion': 'testBuildVersion',\n"
        + "      'cajoledDate': 0\n"
        + "    });\n"
        + "}"
        );
    StringBuilder sb = new StringBuilder();
    sb.append(prefix);
    for (String line : lines) {
      sb.append("        ").append(line).append('\n');
    }
    sb.append(suffix);
    return sb.toString();
  }
}
