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

package com.google.caja.parser.js;

import com.google.caja.parser.MutableParseTreeNode;

/**
 *
 * @author mikesamuel@gmail.com
 */
public interface Expression extends MutableParseTreeNode {
  boolean isLeftHandSide();

  /**
   * Returns an expression that has identical side effects, but that may return
   * a different result.
   * @return null if there are no side effects.
   */
  Expression simplifyForSideEffect();

  /**
   * Returns the result of evaluating the expression in a boolean context or
   * null if indeterminable.  This result is valid assuming that the expression
   * does not throw an exception.  If the expression provably always throws
   * an exception, then it may return any result.
   */
  Boolean conditionResult();
}
