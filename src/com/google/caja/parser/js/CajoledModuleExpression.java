// Copyright (C) 2009 Google Inc.
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

import com.google.caja.lexer.FilePosition;
import com.google.caja.parser.quasiliteral.QuasiBuilder;
import com.google.caja.reporting.RenderContext;

import java.util.List;

/**
 * Wraps a cajoled module to be an expression, which evaluates to a function
 * module
 * 
 * @author maoziqing@gmail.com
 */
public class CajoledModuleExpression extends AbstractExpression {
  @ReflectiveCtor
  public CajoledModuleExpression(FilePosition pos,
      Void value,
      List<? extends CajoledModule> children) {
    this(pos, children.get(0));
    assert children.size() == 1;
  }
  
  public CajoledModuleExpression(
      FilePosition pos, CajoledModule cajoledModule) {
    super(pos, CajoledModule.class);
    createMutation().appendChild(cajoledModule).execute();    
  }
  
  public CajoledModuleExpression(CajoledModule cajoledModule) {
    this(cajoledModule.getFilePosition(), cajoledModule);
  }  

  @Override
  public Object getValue() {
    return null;
  }

  public CajoledModule getCajoledModule() {
    return childrenAs(CajoledModule.class).get(0);
  }

  @Override
  public void render(RenderContext r) {
    ObjectConstructor oc = getCajoledModule().getModuleBody();
    FunctionConstructor fc = 
      ((FunctionConstructor) oc.getValue("instantiate"));
    
    Expression e = (Expression) QuasiBuilder.substV(
      "___.prepareModule(@module);",
      "module", oc);
    
    e.render(r);
  }
}
