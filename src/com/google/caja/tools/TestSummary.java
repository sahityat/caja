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

package com.google.caja.tools;

import com.google.caja.util.FailureIsAnOption;

import java.io.File;
import java.lang.reflect.Method;
import javax.xml.transform.Source;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMResult;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.DirectoryScanner;
import org.apache.tools.ant.Project;
import org.apache.tools.ant.Task;
import org.apache.tools.ant.types.FileSet;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Generates a test summary that realizes that
 * {@link com.google.caja.util.FailureIsAnOption}.
 *
 * <h2>Usage</h2>
 * <pre>
 * &lt;taskdef name="summarize" classname="com.google.caja.tools.TestSummary"
 *  classpathref="..." /&gt;
 * &lt;summarize errorProperty="property.name"&gt;
 *   &lt;fileset dir="..."&gt;
 *     &lt;include ...&gt;
 *   &lt;/fileset&gt;
 * &lt;/summarize&gt;
 * </pre>
 *
 * To see all the failures as if this were disabled, you can run tests like
 * <pre>$ ant reportAllFailure runtests</pre>
 * By default, failure is an option.
 *
 * @author mikesamuel@gmail.com
 */
public final class TestSummary extends Task {
  private FileSet testResults;
  private String errorProperty;
  private String failureProperty;

  @Override
  public void execute() {
    if (testResults == null) {
      throw new BuildException(
          "Please specify a test summary file via the testSummary attribute");
    }

    boolean isFailureAnOptionOverride = !"true".equals(getProject().getProperty(
        "test.failureNotAnOption"));
    log("failureAnOption=" + isFailureAnOptionOverride, Project.MSG_VERBOSE);

    int errors = 0;
    int failures = 0;
    int expectedErrors = 0;
    int expectedFailures = 0;

    DirectoryScanner scanner = testResults.getDirectoryScanner(getProject());
    File baseDir = scanner.getBasedir();
    scanner.scan();
    for (String resultFileName : scanner.getIncludedFiles()) {
      File resultFile = new File(baseDir, resultFileName);
      log("processing file " + resultFileName, Project.MSG_VERBOSE);
      Document result;
      try {
        Source src = new StreamSource(resultFile);
        DOMResult out = new DOMResult();
        TransformerFactory.newInstance().newTransformer().transform(src, out);
        result = (Document) out.getNode();
      } catch (TransformerException ex) {
        throw new BuildException(
            "Failed to read test input from " + resultFileName, ex);
      }

      NodeList testCases = result.getElementsByTagName("testcase");
      for (int i = 0, n = testCases.getLength(); i < n; ++i) {
        Element testCase = (Element) testCases.item(i);
        String className = testCase.getAttribute("classname");
        String methodName = testCase.getAttribute("name");

        Method method;
        try {
          Class<?> testClass = Class.forName(className);
          method = testClass.getMethod(methodName, new Class[0]);
        } catch (ClassNotFoundException ex) {
          throw new BuildException("Cannot find class " + className, ex);
        } catch (NoSuchMethodException ex) {
          throw new BuildException(
              "Cannot find method " + methodName + " of " + className, ex);
        }

        boolean isFailureAnOption = method.isAnnotationPresent(
            FailureIsAnOption.class) && isFailureAnOptionOverride;

        if (isFailureAnOption) {
          log("Failure is an option for " + className + "." + methodName,
              Project.MSG_VERBOSE);

          // Turn any failures and errors into different elements, so they're
          // not apparent to later stages, but accessible to the XSL used to
          // render the HTML reports.
          int f = rewriteChildElements(testCase, "failure", "expected-failure");
          int e = rewriteChildElements(testCase, "error", "expected-error");

          // Keep track of the number of expected failures.
          int nErrors = testCase.getElementsByTagName("expected-error")
              .getLength();
          int nFailures = testCase.getElementsByTagName("expected-failure")
              .getLength();
          expectedErrors += nErrors;
          expectedFailures += nFailures;

          // Warn, so that we can remove expected failure annotations.
          if (nErrors == 0 && nFailures == 0) {
            hero(className, methodName);
          }

          // Attach info to the test case so that the test XSL can render
          // expected failures in a different color.
          testCase.setAttribute("expected", "true");
          subtract(e, f, (Element) testCase.getParentNode());
        } else {
          // Undo changes made if tests were previously run without the
          // override.
          int f = rewriteChildElements(testCase, "expected-failure", "failure");
          int e = rewriteChildElements(testCase, "expected-error", "error");

          testCase.removeAttribute("expected");
          subtract(-e, -f, (Element) testCase.getParentNode());

          failures += testCase.getElementsByTagName("failure").getLength();
          errors += testCase.getElementsByTagName("error").getLength();
        }
      }

      try {
        DOMSource src = new DOMSource(result);
        StreamResult out = new StreamResult(resultFile);
        TransformerFactory.newInstance().newTransformer().transform(src, out);
      } catch (TransformerException ex) {
        throw new BuildException("Failed to write result " + resultFileName);
      }
    }

    if (failureProperty != null && failures != 0) {
      this.getProject().setProperty(failureProperty, "" + failures);
    }
    if (errorProperty != null && errors != 0) {
      this.getProject().setProperty(errorProperty, "" + errors);
    }

    if ((errors | expectedErrors | failures | expectedFailures) != 0) {
      log("Errors: " + errors
          + ", Failures: " + failures
          + ", Expected Errors: " + expectedErrors
          + ", Expected Failures: " + expectedFailures,
          Project.MSG_WARN);
    }
  }

  private void hero(String className, String testName) {
    // Gold star for you.
    log("Expected failure but " + testName + " on " + className + " succeeded",
        Project.MSG_INFO);
  }

  private static void subtract(int nErrors, int nFailures, Element el) {
    while ("testsuite".equals(el.getTagName())) {
      el.setAttribute(
          "failures",
          "" + (Integer.valueOf(el.getAttribute("failures")) - nFailures));
      el.setAttribute(
          "errors",
          "" + (Integer.valueOf(el.getAttribute("errors")) - nErrors));
      Node parent = el.getParentNode();
      if (!(parent instanceof Element)) { break; }
      el = (Element) parent;
    }
  }

  private static int rewriteChildElements(
      Element parent, String oldName, String newName) {
    int nRewritten = 0;
    for (Node c = parent.getFirstChild(); c != null; c = c.getNextSibling()) {
      if (c instanceof Element && oldName.equals(c.getNodeName())) {
        Element el = (Element) c;
        Element replacement = parent.getOwnerDocument().createElement(newName);
        while (el.getFirstChild() != null) {
          replacement.appendChild(el.getFirstChild());
        }
        NamedNodeMap attrMap = el.getAttributes();
        Attr[] attrs = new Attr[attrMap.getLength()];
        for (int i = 0, n = attrMap.getLength(); i < n; ++i) {
          attrs[i] = (Attr) attrMap.item(i);
        }
        for (Attr attr : attrs) {
          el.removeAttributeNode(attr);
          replacement.setAttributeNode(attr);
        }
        parent.replaceChild(replacement, el);
        ++nRewritten;
      }
    }
    return nRewritten;
  }

  // Called reflectively by ant.
  public void setErrorProperty(String name) { this.errorProperty = name; }
  public void setFailureProperty(String name) { this.failureProperty = name; }
  public FileSet createFileSet() {
    if (this.testResults != null) {
      throw new BuildException("Can only have one file set");
    }
    return this.testResults = new FileSet();
  }
}
