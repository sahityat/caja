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

package com.google.caja.parser.quasiliteral;

import com.google.caja.lexer.FilePosition;
import com.google.caja.parser.ParseTreeNode;
import com.google.caja.parser.ParseTreeNodeContainer;
import com.google.caja.parser.js.Block;
import com.google.caja.parser.js.CatchStmt;
import com.google.caja.parser.js.Declaration;
import com.google.caja.parser.js.Expression;
import com.google.caja.parser.js.ExpressionStmt;
import com.google.caja.parser.js.FormalParam;
import com.google.caja.parser.js.FunctionConstructor;
import com.google.caja.parser.js.FunctionDeclaration;
import com.google.caja.parser.js.Identifier;
import com.google.caja.parser.js.NullLiteral;
import com.google.caja.parser.js.Reference;
import com.google.caja.parser.js.Statement;
import com.google.caja.reporting.MessagePart;
import com.google.caja.reporting.MessageQueue;
import com.google.caja.util.Lists;
import com.google.caja.util.Maps;

import java.util.Collections;
import java.util.List;
import java.util.Map;

final class AlphaRenamingRewriter extends Rewriter {
  private final Map<Scope, NameContext<String, ?>> contexts
      = Maps.newIdentityHashMap();

  AlphaRenamingRewriter(
      final MessageQueue mq, final NameContext<String, ?> rootContext) {
    super(mq, false, false);

    addRules(new Rule[] {
      /////////////////
      // Scope Rules //
      /////////////////

        new Rule() {
        @Override
        @RuleDescription(
            name="rootScope",
            synopsis="introduces a root scope",
            reason="lets us rename globals",
            matches="/* Expression */ @e",
            substitutes="@e")
        public ParseTreeNode fire(ParseTreeNode node, Scope scope) {
          if (node instanceof Expression && scope == null) {
            Expression e = (Expression) node;
            Block bl = new Block(
                e.getFilePosition(),
                Collections.singletonList(new ExpressionStmt(e)));
            scope = Scope.fromProgram(bl, mq);
            contexts.put(scope, rootContext);
            return expand(e, scope);
          }
          return NONE;
        }
      },

      new Rule() {
        @Override
        @RuleDescription(
            name="fns",
            synopsis=("introduces function scope and assigns rewritten names"
                      + " for function names, formals, and locals"),
            reason="",
            matches="function @name?(@params*) { @body* }",
            substitutes="function @name?(@params*) { @selfDecl?; @body* }")
        public ParseTreeNode fire(ParseTreeNode node, Scope scope) {
          FunctionConstructor fc;

          if (node instanceof FunctionConstructor) {
            fc = (FunctionConstructor) node;
          } else if (node instanceof FunctionDeclaration) {
            fc = ((FunctionDeclaration) node).getInitializer();
          } else {
            fc = null;
          }
          Map<String, ParseTreeNode> bindings = fc != null ? match(fc) : null;
          if (bindings != null) {
            boolean isDeclaration = fc != node;
            NameContext<String, ?> context = contexts.get(scope);
            NameContext<String, ?> newContext = context.makeChildContext();
            Scope newScope = Scope.fromFunctionConstructor(scope, fc);
            for (String local : newScope.getLocals()) {
              if (!"this".equals(local) && !"arguments".equals(local)) {
                try {
                  newContext.declare(
                      local, newScope.getLocationOfDeclaration(local));
                } catch (NameContext.RedeclarationException ex) {
                  // Should never occur since locals must be a set.
                  throw new RuntimeException(ex);
                }
              }
            }
            contexts.put(newScope, newContext);

            Identifier name = fc.getIdentifier();
            Identifier rewrittenName;
            if (name.getName() == null) {
              rewrittenName = name;
            } else if (!isSynthetic(name)) {
              rewrittenName = new Identifier(
                  name.getFilePosition(),
                  (isDeclaration ? context : newContext)
                  .lookup(name.getName()).newName);
            } else {
              rewrittenName = name;
            }

            List<FormalParam> newFormals = Lists.newArrayList();
            for (FormalParam p : fc.getParams()) {
              if (!isSynthetic(p.getIdentifier())) {
                FormalParam newP = new FormalParam(new Identifier(
                    p.getFilePosition(),
                    newContext.lookup(p.getIdentifierName()).newName));
                newFormals.add(newP);
              } else {
                newFormals.add(p);
              }
            }

            Declaration selfDecl = null;
            if (isDeclaration && !isSynthetic(name)
                // If there is a local declaration inside the local scope that
                // masks the function name, then don't clobber it.
                && newScope.isFunction(name.getName())
                && !newScope.isDeclaredFunction(name.getName())) {
              // For a declaration, a name is introduced in both the scope
              // containing the function, and the function body scope.
              // We produce a declaration with the outer name, but in the inner
              // scope, the function name should always refer to itself.
              selfDecl = (Declaration) QuasiBuilder.substV(
                  "var @innerName = @outerName;",
                  "outerName", new Reference(rewrittenName),
                  "innerName", new Identifier(
                      name.getFilePosition(),
                      newContext.lookup(name.getName()).newName));
              // TODO(mikesamuel): skip if the self name is never used.
            }

            FunctionConstructor out = (FunctionConstructor) substV(
                "name", rewrittenName,
                "selfDecl", selfDecl,
                "params", new ParseTreeNodeContainer(newFormals),
                "body", expandAll(bindings.get("body"), newScope));
            out.setFilePosition(fc.getFilePosition());
            return isDeclaration ? new FunctionDeclaration(out) : out;
          }
          return NONE;
        }
      },

      new Rule() {
        @Override
        @RuleDescription(
            name="block",
            synopsis="block scoping",
            reason="",
            matches="{ @body* }",
            substitutes="{ @body* }")
        public ParseTreeNode fire(ParseTreeNode node, Scope scope) {
          if (node instanceof Block) {
            Block bl = (Block) node;
            List<Statement> stmts = Lists.newArrayList();
            Scope newScope = Scope.fromPlainBlock(scope);
            NameContext<String, ?> newContext = contexts.get(scope)
                .makeChildContext();
            contexts.put(newScope, newContext);
            for (String local : newScope.getLocals()) {
              try {
                newContext.declare(
                    local, newScope.getLocationOfDeclaration(local));
              } catch (NameContext.RedeclarationException ex) {
                // Should never occur since locals must be a set.
                throw new RuntimeException(ex);
              }
            }
            for (Statement s : bl.children()) {
              stmts.add((Statement) expand(s, newScope));
            }
            stmts.addAll(0, newScope.getStartStatements());
            return new Block(bl.getFilePosition(), stmts);
          }
          return NONE;
        }
      },

      new Rule() {
        @Override
        @RuleDescription(
            name="catch",
            synopsis="catch block scoping",
            reason="",
            matches="catch (@e) { @body* }",
            substitutes="catch (@e) { @body* }")
        public ParseTreeNode fire(ParseTreeNode node, Scope scope) {
          if (node instanceof CatchStmt) {
            CatchStmt cs = (CatchStmt) node;
            Scope newScope = Scope.fromCatchStmt(scope, cs);
            NameContext<String, ?> context = contexts.get(scope);
            NameContext<String, ?> newContext = context.makeChildContext();
            contexts.put(newScope, newContext);
            try {
              newContext.declare(cs.getException().getIdentifierName(),
                                 cs.getException().getFilePosition());
            } catch (NameContext.RedeclarationException ex) {
              ex.toMessageQueue(mq);
            }
            return expandAll(cs, newScope);
          }
          return NONE;
        }
      },

      //////////////
      // Renaming //
      //////////////

      new Rule() {
        @Override
        @RuleDescription(
            name="memberAccess",
            synopsis="",
            reason="so that we do not mistakenly rename property names",
            matches="@o.@r",
            substitutes="@o.@r")
        public ParseTreeNode fire(ParseTreeNode node, Scope scope) {
          Map<String, ParseTreeNode> bindings = match(node);
          if (bindings != null) {
            return substV("o", expand(bindings.get("o"), scope),
                          "r", bindings.get("r"));
          }
          return NONE;
        }
      },
      new Rule() {
        @Override
        @RuleDescription(
            name="thisReference",
            synopsis="Don't rewrite 'this'.",
            reason="The declaration cannot be rewritten.",
            matches="this",
            substitutes="this")
            public ParseTreeNode fire(ParseTreeNode node, Scope scope) {
          if (match(node) != null) {
            if (scope.isOuter()) {
              mq.addMessage(
                  RewriterMessageType.THIS_IN_GLOBAL_CONTEXT,
                  node.getFilePosition());
              return new NullLiteral(node.getFilePosition());
            }
            return node;
          }
          return NONE;
        }
      },
      new Rule() {
        @Override
        @RuleDescription(
            name="argumentsReference",
            synopsis="Don't rewrite 'arguments'.",
            reason="The declaration cannot be rewritten.",
            matches="arguments",
            substitutes="arguments")
            public ParseTreeNode fire(ParseTreeNode node, Scope scope) {
          if (match(node) != null) {
            if (scope.isOuter()) {
              mq.addMessage(
                  RewriterMessageType.ARGUMENTS_IN_GLOBAL_CONTEXT,
                  node.getFilePosition());
              return new NullLiteral(node.getFilePosition());
            }
            return node;
          }
          return NONE;
        }
      },
      new Rule() {
        @Override
        @RuleDescription(
            name="rename",
            synopsis="",
            reason="",
            matches="/* Reference */ @r",
            substitutes="@r")
        public ParseTreeNode fire(ParseTreeNode node, Scope scope) {
          if (node instanceof Reference) {
            Reference r = (Reference) node;
            if (!isSynthetic(r)) {
              FilePosition pos = r.getFilePosition();
              String rname = r.getIdentifierName();
              NameContext<String, ?> context = contexts.get(scope);
              NameContext.VarInfo<String, ?> vi = context.lookup(rname);
              if (vi != null) {
                return new Reference(new Identifier(pos, vi.newName));
              } else {
                mq.addMessage(
                    RewriterMessageType.FREE_VARIABLE, pos,
                    MessagePart.Factory.valueOf(rname));
                return new NullLiteral(pos);
              }
            }
          }
          return NONE;
        }
      },
      new Rule() {
        @Override
        @RuleDescription(
            name="decl",
            synopsis="rewrite declaration identifiers",
            reason="",
            matches="var @i = @v?",
            substitutes="var @ri = @v?;")
        public ParseTreeNode fire(ParseTreeNode node, Scope scope) {
          Map<String, ParseTreeNode> bindings = match(node);
          if (bindings != null) {
            Identifier i = (Identifier) bindings.get("i");
            Expression v = (Expression) bindings.get("v");
            Identifier ri;
            if (!isSynthetic(i)) {
              NameContext<String, ?> context = contexts.get(scope);
              NameContext.VarInfo<String, ?> var = context.lookup(i.getName());
              if (var == null) {  // A variable like arguments
                return expandAll(node, scope);
              }
              ri = new Identifier(i.getFilePosition(), var.newName);
            } else {
              ri = i;
            }
            return substV(
                "ri", ri,
                "v", v != null ? expand(v, scope) : null);
          }
          return NONE;
        }
      },
      new Rule() {
        @Override
        @RuleDescription(
            name="other",
            synopsis="",
            reason="",
            matches="@n",
            substitutes="@n")
        public ParseTreeNode fire(ParseTreeNode node, Scope scope) {
          return expandAll(node, scope);
        }
      },
    });
  }
}
