// Copyright (C) 2007 Google Inc.
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

import com.google.caja.lexer.CharProducer;
import com.google.caja.lexer.ExternalReference;

/**
 * Specifies how the cajoler resolves external resources such as scripts and
 * stylesheets in the code being cajoled.
 *
 * @author mikesamuel@gmail.com
 */
public interface PluginEnvironment {

  /**
   * Loads an external resource such as the {@code src} of a {@code script}
   * tag or a stylesheet.
   *
   * @return null if the resource could not be loaded.
   */
  CharProducer loadExternalResource(ExternalReference ref, String mimeType);

  /**
   * Applies a URI policy and returns a URI that enforces that policy.
   *
   * @return null if the URI cannot be made safe.
   */
  String rewriteUri(ExternalReference uri, String mimeType);

  /** A plugin environment that will not resolve or rewrite any URI. */
  public static final PluginEnvironment CLOSED_PLUGIN_ENVIRONMENT
      = new PluginEnvironment() {
        public CharProducer loadExternalResource(
          ExternalReference ref, String mimeType) {
          return null;
        }

        public String rewriteUri(ExternalReference uri, String mimeType) {
          return null;
        }
      };
}
