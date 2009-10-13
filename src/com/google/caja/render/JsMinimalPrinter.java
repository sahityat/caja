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

import com.google.caja.lexer.FilePosition;
import java.util.ArrayList;
import java.util.List;

/**
 * A compact JavaScript renderer.
 *
 * @author mikesamuel@gmail.com
 */
public final class JsMinimalPrinter extends BufferingRenderer {
  /** Number of characters written to out since the last linebreak. */
  private int charInLine;
  /**
   * Line length below which the printer will not wrap lines.
   * At or above this limit, the printer will try to replace a space with
   * a line-break.
   */
  private int lineLengthLimit = 80;
  /** The last non-space/comment token. */
  private String lastToken;
  private JsTokenAdjacencyChecker adjChecker = new JsTokenAdjacencyChecker();

  /**
   * @param out receives the rendered text.
   */
  public JsMinimalPrinter(Concatenator out) {
    super(out);
  }

  /** Visible for testing.  Should not be used by clients. */
  void setLineLengthLimit(int lineLengthLimit) {
    this.lineLengthLimit = lineLengthLimit;
  }

  @Override
  List<String> splitTokens(List<Object> tokens) {
    List<String> outputTokens = new ArrayList<String>();

    for (Object tokenEl : tokens) {
      if (tokenEl instanceof FilePosition) { continue; }
      String text = (String) tokenEl;
      TokenClassification tClass = TokenClassification.classify(text);
      if (tClass == null) { continue; }
      switch (tClass) {
        case LINEBREAK:
        case SPACE:
        case COMMENT:
          continue;
        default: break;
      }

      // Write any whitespace before the token.
      if (adjChecker.needSpaceBefore(text)) {
        // Some security tools/proxies/firewalls break on really long javascript
        // lines.
        if (charInLine >= lineLengthLimit
            && JsRenderUtil.canBreakBetween(lastToken, text)) {
          charInLine = 0;
          outputTokens.add("\n");
        } else if (charInLine != 0) {
          ++charInLine;
          outputTokens.add(" ");
        }
      }

      // Actually write the token.
      charInLine += text.length();
      if ("}".equals(text) && ";".equals(lastToken)) {
        // ES5 Section 7.9.1 Rules of Automatic Semicolon Insertion
        // When, as the program is parsed from left to right, a token (called
        // the offending token) is encountered that is not allowed by any
        // production of the grammar, then a semicolon is automatically inserted
        // before the offending token if one or more of the following conditions
        // is true:
        // ...
        // 2.   The offending token is }.

        // NOTE: this could turn an invalid token sequence into a valid one.
        // E.g. ({ a: 0; }) => ({ a: 0 })
        //             ^
        outputTokens.set(outputTokens.size() - 1, text);
      } else {
        outputTokens.add(text);
      }
      lastToken = text;
    }
    // ES5 Section 7.9.1 Rules of Automatic Semicolon Insertion
    // When, as the program is parsed from left to right, the end of the input
    // stream of tokens is encountered and the parser is unable to parse the
    // input token stream as a single complete ECMAScript Program, then a
    // semicolon is automatically inserted at the end of the input stream.
    if (";".equals(lastToken)) { outputTokens.remove(outputTokens.size() - 1); }
    return outputTokens;
  }
}
