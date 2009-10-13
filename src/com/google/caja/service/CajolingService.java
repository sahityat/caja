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

import com.google.caja.reporting.BuildInfo;
import com.google.caja.util.Pair;
import com.google.caja.lexer.ExternalReference;
import com.google.caja.lexer.InputSource;
import com.google.caja.opensocial.UriCallback;
import com.google.caja.opensocial.UriCallbackException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Vector;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * A cajoling service which proxies connections:<ul>
 *   <li> cajole any javascript
 *   <li> cajoles any gadgets
 *   <li> checks requested and retrieved mime-types
 * </ul>
 *
 * @author jasvir@gmail.com (Jasvir Nagra)
 */
public class CajolingService extends HttpServlet {
  private List<ContentHandler> handlers = new Vector<ContentHandler>();
  private ContentTypeCheck typeCheck = new LooseContentTypeCheck();
  private String host = "http://caja.appspot.com/cajoler";

  public CajolingService(BuildInfo buildInfo) {
    registerHandlers(buildInfo);
  }

  public CajolingService(BuildInfo buildInfo, String host) {
    this.host = host;
    registerHandlers(buildInfo);
  }

  /**
   * Read the remainder of the input request, send a BAD_REQUEST http status
   * to browser and close the connection
   */
  private static void closeBadRequest(HttpServletResponse resp)
      throws ServletException {
    try {
      resp.sendError(HttpServletResponse.SC_FORBIDDEN);
      resp.getWriter().close();
    } catch (IOException ex) {
      throw (ServletException) new ServletException().initCause(ex);
    }
  }

  /**
   * Fetch query parameter from request
   */
  private String getParam(HttpServletRequest r, String param, boolean required)
    throws ServletException {
    String result = r.getParameter(param);
    if (required && result == null) {
      throw new ServletException(
        "Missing parameter \"" + param + "\" is required: " +
          r.getRequestURI());
    }
    return result;
  }

  @Override
  public void doPost(HttpServletRequest req, HttpServletResponse resp)
      throws ServletException {
    if (req.getContentType() == null) {
      closeBadRequest(resp);
      return;
    }

    FetchedData fetchedData;
    try {
      fetchedData = new FetchedData(req.getInputStream(),
          req.getContentType(), req.getCharacterEncoding());
    } catch (IOException e) {
      closeBadRequest(resp);
      return;
    }

    String inputUrlString = getParam(req, "url", false /* required */);
    URI inputUri;
    if (inputUrlString == null) {
      inputUri = InputSource.UNKNOWN.getUri();
    } else {
      try {
        inputUri = new URI(inputUrlString);
      } catch (URISyntaxException ex) {
        throw (ServletException) new ServletException().initCause(ex);
      }
    }

    handle(req, resp, inputUri, fetchedData);
  }

  @Override
  public void doGet(HttpServletRequest req, HttpServletResponse resp)
      throws ServletException {
    String inputUrlString = getParam(req, "url", true /* required */);
    URI inputUri;
    try {
      inputUri = new URI(inputUrlString);
    } catch (URISyntaxException ex) {
      throw (ServletException) new ServletException().initCause(ex);
    }

    String expectedInputContentType = getParam(req, "mime-type",
        false /* required */);
    if (expectedInputContentType == null) {
      expectedInputContentType = getParam(req, "input-mime-type",
          true /* required */);
    }

    FetchedData fetchedData;
    try {
      fetchedData = fetch(inputUri);
    } catch (IOException ex) {
      closeBadRequest(resp);
      return;
    }

    if (!typeCheck.check(expectedInputContentType,
            fetchedData.getContentType())) {
      closeBadRequest(resp);
      return;
    }

    handle(req, resp, inputUri, fetchedData);
  }

  private void handle(HttpServletRequest req, HttpServletResponse resp,
                      URI inputUri, FetchedData fetchedData)
      throws ServletException {
    String outputContentType = getParam(req, "output-mime-type",
        false /* required */);
    if (outputContentType == null) {
      outputContentType = "*/*";
    }

    Transform transform;
    try {
      transform = Transform.valueOf(
          getParam(req, "transform", false /* required */));
    } catch (Exception e ) {
      transform = null;
    }

    ByteArrayOutputStream intermediateResponse = new ByteArrayOutputStream();
    Pair<String, String> contentInfo;
    try {
      contentInfo = applyHandler(
          inputUri,
          transform, fetchedData.getContentType(), outputContentType,
          fetchedData.getCharSet(), fetchedData.getContent(),
          intermediateResponse);
    } catch (UnsupportedContentTypeException e) {
      closeBadRequest(resp);
      return;
    }

    byte[] response = intermediateResponse.toByteArray();
    int responseLength = response.length;

    resp.setStatus(HttpServletResponse.SC_OK);
    String responseContentType = contentInfo.a;
    if (contentInfo.b != null) {
      responseContentType += ";charset=" + contentInfo.b;
    }
    if (containsNewline(responseContentType)) {
      throw new IllegalArgumentException(responseContentType);
    }
    resp.setContentType(responseContentType);
    resp.setContentLength(responseLength);

    try {
      resp.getOutputStream().write(response);
      resp.getOutputStream().close();
    } catch (IOException ex) {
      throw (ServletException) new ServletException().initCause(ex);
    }
  }

  public void registerHandlers(BuildInfo buildInfo) {
    UriCallback retriever = new UriCallback() {
      public Reader retrieve(ExternalReference extref, String mimeType)
          throws UriCallbackException {
        try {
          FetchedData data = fetch(extref.getUri());
          return new InputStreamReader(
              new ByteArrayInputStream(data.getContent()), data.getCharSet());
        } catch (IOException ex) {
          throw new UriCallbackException(extref, ex);
        }
      }

      public URI rewrite(ExternalReference extref, String mimeType) {
        return null;
      }
    };
    handlers.add(new JsHandler(buildInfo));
    handlers.add(new ImageHandler());
    handlers.add(new GadgetHandler(buildInfo, retriever));
    handlers.add(new InnocentHandler());
    handlers.add(new HtmlHandler(buildInfo, host, retriever));
  }

  protected FetchedData fetch(URI uri) throws IOException {
    return new FetchedData(uri);
  }

  private Pair<String, String> applyHandler(
      URI uri, Transform t, String inputContentType, String outputContentType,
      String charSet, byte[] content, OutputStream response)
      throws UnsupportedContentTypeException {
    for (ContentHandler handler : handlers) {
      if (handler.canHandle(uri, t, inputContentType,
              outputContentType, typeCheck)) {
        return handler.apply(uri, t, inputContentType,
            outputContentType, charSet, content, response);
      }
    }
    throw new UnsupportedContentTypeException();
  }

  // Used to protect against header splitting attacks.
  private static boolean containsNewline(String s) {
    return s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0;
  }

  public static enum Transform {
    INNOCENT,
    VALIJA,
    CAJITA;
  }
}
