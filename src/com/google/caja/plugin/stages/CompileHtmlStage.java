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

package com.google.caja.plugin.stages;

import com.google.caja.lang.css.CssSchema;
import com.google.caja.lang.html.HtmlSchema;
import com.google.caja.parser.AncestorChain;
import com.google.caja.parser.css.CssTree;
import com.google.caja.parser.html.DomParser;
import com.google.caja.parser.js.Block;
import com.google.caja.plugin.Dom;
import com.google.caja.plugin.Job;
import com.google.caja.plugin.Jobs;
import com.google.caja.plugin.templates.TemplateCompiler;
import com.google.caja.plugin.templates.TemplateSanitizer;
import com.google.caja.reporting.MessageQueue;
import com.google.caja.util.Pair;
import com.google.caja.util.Pipeline;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.w3c.dom.Node;

/**
 * Compile the HTML and CSS to javascript.
 *
 * @author mikesamuel@gmail.com
 */
public final class CompileHtmlStage implements Pipeline.Stage<Jobs> {
  private final CssSchema cssSchema;
  private final HtmlSchema htmlSchema;

  public CompileHtmlStage(CssSchema cssSchema, HtmlSchema htmlSchema) {
    if (null == cssSchema) { throw new NullPointerException(); }
    if (null == htmlSchema) { throw new NullPointerException(); }
    this.cssSchema = cssSchema;
    this.htmlSchema = htmlSchema;
  }

  public boolean apply(Jobs jobs) {
    List<Node> ihtmlRoots = new ArrayList<Node>();
    List<CssTree.StyleSheet> stylesheets = new ArrayList<CssTree.StyleSheet>();

    for (Iterator<Job> jobIt = jobs.getJobs().iterator(); jobIt.hasNext();) {
      Job job = jobIt.next();
      switch (job.getType()) {
        case HTML:
          // TODO(ihab.awad): We do *not* want to support multiple HTML files
          // being cajoled at once since this can be mis-used as a "modularity"
          // system and we set up expectations on the part of our users to
          // maintain this behavior, regardless of whatever complexity that
          // might entail.
          ihtmlRoots.add(job.getRoot().cast(Dom.class).node.getValue());
          jobIt.remove();
          break;
        case CSS:
          stylesheets.add(job.getRoot().cast(CssTree.StyleSheet.class).node);
          jobIt.remove();
          break;
        default: break;
      }
    }

    MessageQueue mq = jobs.getMessageQueue();

    TemplateSanitizer ts = new TemplateSanitizer(htmlSchema, mq);
    for (Node ihtmlRoot : ihtmlRoots) { ts.sanitize(ihtmlRoot); }
    TemplateCompiler tc = new TemplateCompiler(
        ihtmlRoots, stylesheets, cssSchema, htmlSchema,
        jobs.getPluginMeta(), jobs.getMessageContext(), mq);
    Pair<Node, List<Block>> htmlAndJs = tc.getSafeHtml(
        DomParser.makeDocument(null, null));

    jobs.getJobs().add(new Job(AncestorChain.instance(new Dom(htmlAndJs.a))));
    for (Block bl : htmlAndJs.b) {
      jobs.getJobs().add(new Job(AncestorChain.instance(bl)));
    }

    return jobs.hasNoFatalErrors();
  }
}
