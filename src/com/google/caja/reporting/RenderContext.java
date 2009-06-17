// Copyright (C) 2005 Google Inc.
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

package com.google.caja.reporting;

import com.google.caja.lexer.TokenConsumer;

/**
 * @see Renderable
 * @author mikesamuel@gmail.com
 */
public class RenderContext {

  /** Produce output that can be safely embedded. */
  private final boolean embeddable;
  /** Produce output that only contains lower 7-bit characters. */
  private final boolean asciiOnly;
  /** Should javascript output be rendered using JSON conventions. */
  private final boolean json;
  /** True iff DOM tree nodes should be rendered as XML. */
  private final boolean asXml;
  private final TokenConsumer out;

  public RenderContext(TokenConsumer out) {
    this(true, false, false, false, out);
  }

  private RenderContext(
      boolean asciiOnly, boolean embeddable, boolean json, boolean asXml,
      TokenConsumer out) {
    if (null == out) { throw new NullPointerException(); }
    this.embeddable = embeddable;
    this.asciiOnly = asciiOnly;
    this.json = json;
    this.asXml = asXml;
    this.out = out;
  }

  /**
   * True if the renderer produces output that can be embedded inside a CDATA
   * section, or {@code script} element without further escaping?
   */
  public final boolean isEmbeddable() { return embeddable; }
  /**
   * True if the renderer produces output that only contains characters in
   * {@code [\1-\x7f]}.
   */
  public final boolean isAsciiOnly() { return asciiOnly; }
  public final boolean asJson() { return json; }
  /** True iff DOM tree nodes should be rendered as XML. */
  public final boolean asXml() { return asXml; }
  public final TokenConsumer getOut() { return out; }

  public RenderContext withAsciiOnly(boolean b) {
    return b != asciiOnly
        ? new RenderContext(b, embeddable, json, asXml, out) : this;
  }
  public RenderContext withEmbeddable(boolean b) {
    return b != embeddable
        ? new RenderContext(asciiOnly, b, json, asXml, out) : this;
  }
  public RenderContext withJson(boolean b) {
    return b != json
        ? new RenderContext(asciiOnly, embeddable, b, asXml, out) : this;
  }
  public RenderContext withAsXml(boolean b) {
    return b != this.asXml
        ? new RenderContext(asciiOnly, embeddable, json, b, out) : this;
  }
}
