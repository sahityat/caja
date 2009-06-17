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

package com.google.caja.lexer.escaping;

import com.google.caja.util.Join;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utilities for dealing with URIs.
 *
 * @author mikesamuel@gmail.com
 */
public class UriUtil {

  /** Matches a URI extracting bits with different escaping conventions. */
  private static final Pattern RFC_2396 = Pattern.compile(
      // Derived from RFC 2396 Appendix B
      //   1                 2          3             4            5
      "^(?:([^:/?#]+):)?(?://([^/?#]*))?([^?#]*)(?:\\?([^#]*))?(?:#(.*))?$",
      Pattern.DOTALL);

  /**
   * Convert a URI to a string, %xx escaping some codepoints that are in the
   * RFC3986 reserved set, but not in contexts where they are significant.
   * This works around problems with inconsistencies in escaping conventions
   * in CSS URIs, but still allows us to make sure that URIs don't look like
   * code that a badly written error recovery routine might jump into.
   * <p>
   * This escaping of codepoints is allowed by section 2.4.2 of RFC 2396:
   * <blockquote>
   *   In some cases, data that could be represented by an unreserved
   *   character may appear escaped; for example, some of the unreserved
   *   "mark" characters are automatically escaped by some systems.  If the
   *   given URI scheme defines a canonicalization algorithm, then
   *   unreserved characters may be unescaped according to that algorithm.
   *   For example, "%7e" is sometimes used instead of "~" in an http URL
   *   path, but the two are equivalent for an http URL.
   * </blockquote>
   *
   * @param uri a non-opaque URI.
   */
  public static String normalizeUri(String uri) {
    // We don't use java.net.URI to recompose the URI because of problems with
    // encoding in the multi-argument constructor as described at
    // http://blog.limewire.org/?p=261:
    //     And it seems that the multi-arg constructors, which do URL encoding
    //     for you, do NOT provide a way for you to encode these characters -
    //     which means you can only ever use them for their reserved (unescaped)
    //     purpose.
    //     For example, suppose I want to produce this URL:
    //       http://foo.com/bar?a=b&c=jon%26doe
    //       uri = new URI("http", null, "foo.com", -1, "/bar",
    //                     "a=b&c=jon%26doe", null);
    //       uri.toASCIIString() -> http://foo.com/bar?a=b&c=jon%2526doe
    //     ...
    //     The upshot of all of this is that I claim the multi-arg constructors
    //     are unusable, unless you restrict your URLs to never use reserved
    //     characters as values. In our use case, we can't do that because we
    //     don't control what URIs are incoming / outgoing.
    Matcher m = RFC_2396.matcher(uri);
    // The RFC_2396 matches all strings.
    m.matches();
    String scheme = m.group(1);
    String authority = m.group(2);
    String path = m.group(3);
    String query = m.group(4);
    String fragment = m.group(5);

    // Path must start with / if the path is not empty or there is an authority
    // or scheme.
    // Remove unnecessary .. components in path.

    StringBuilder sb = new StringBuilder(uri.length());
    if (scheme != null) {
      normalizeScheme(scheme, sb);
      sb.append(':');
    }
    if (authority != null) {
      sb.append("//");
      normalizeAuthority(authority, sb);
    }
    if (path.length() != 0 || sb.length() != 0) {
      normalizePath(path, sb.length() != 0, sb);
    }
    if (query != null) {
      sb.append('?');
      normalizeQuery(query, sb);
    }
    if (fragment != null) {
      sb.append('#');
      normalizeFragment(fragment, sb);
    }
    return sb.toString();
  }

  private static void normalizeScheme(String scheme, StringBuilder out) {
    // Section 3.1:
    // scheme        = alpha *( alpha | digit | "+" | "-" | "." )
    int pos = 0, n = scheme.length();
    for (int i = 0; i < n; ++i) {
      char ch = scheme.charAt(i);
      if (ch == '%') {
        if (isInvalidEsc(scheme, i)) {
          out.append(scheme, pos, i).append("%25");
          pos = i + 1;
        }
      } else if (!(('a' <= ch && ch <= 'z')
            || ('A' <= ch && ch <= 'Z')
            || ('0' <= ch && ch <= '9')
            || ch == '+' || ch == '-' || ch == '.')) {
        out.append(scheme, pos, i);
        pos = i + 1;
        pctEncode(ch, out);
      }
    }
    out.append(scheme, pos, n);
  }

  private static void normalizeAuthority(String authority, StringBuilder out) {
    // Section 3.2:
    // The authority component is preceded by a double slash "//" and is
    // terminated by the next slash "/", question-mark "?", or by the end of
    // the URI.  Within the authority component, the characters ";", ":",
    // "@", "?", and "/" are reserved.

    // The above quote from the RFC ignores the fact that '#' ends the authority
    // in URI references, but the pattern from Appendix B recognizes this.
    // We escape '@' since it is a significant character in CSS in @import
    // directives, as well as in conditional compilation comments.
    // This does not affect web applications in practice, since browsers
    // disallow '@' in authorities in HTTP and HTTPS since only a tiny number of
    // HTTP servers recognized it, but it was widely used by phishers.
    int pos = 0, n = authority.length();
    for (int i = 0; i < n; ++i) {
      char ch = authority.charAt(i);
      if (ch == '%') {
        if (isInvalidEsc(authority, i)) {
          out.append(authority, pos, i).append("%25");
          pos = i + 1;
        }
      } else if (!(('a' <= ch && ch <= 'z')
            || ('A' <= ch && ch <= 'Z')
            || ('0' <= ch && ch <= '9')
            // Escapes ; and @.
            || ch == ':' || ch == '-' || ch == '+' || ch == '.')) {
        out.append(authority, pos, i);
        pos = i + 1;
        pctEncode(ch, out);
      }
    }
    out.append(authority, pos, n);
  }

  private static void normalizePath(
      String path, boolean requireAbsPath, StringBuilder out) {
    String normPath = normalizeEscapesInPath(path);
    boolean isAbs = requireAbsPath;
    if (normPath.startsWith("/")) {
      normPath = normPath.substring(1);
      isAbs = true;
    }
    List<String> pathParts = new ArrayList<String>(
        Arrays.asList(normPath.split("/")));
    int i = 0;
    while (i < pathParts.size()) {
      String dottedPart = pathParts.get(i).replace("%2e", ".")
          .replace("%2E", ".");
      if (".".equals(dottedPart)) {
        pathParts.remove(i);
      } else if ("..".equals(dottedPart)) {
        if (i > 0 && !"..".equals(pathParts.get(i - 1))) {
          // back up over the previous part which will soon contain the next
          // part to process.
          --i;
          pathParts.subList(i, i + 2).clear();
        } else if (isAbs) {  // can't get to parent of the root.
          pathParts.remove(i);
        } else {
          // normalize so the "..".equals(pathParts.get(i - 1)) check above
          // works.
          pathParts.set(i, "..");
          ++i;
        }
      } else {
        ++i;
      }
    }
    if (isAbs) {
      // All paths for absolute URIs must start with '/'.
      // The URL http://foo?bar is not strictly legal.
      out.append('/');
    }
    Join.join(out, "/", pathParts);
  }

  private static String normalizeEscapesInPath(String path) {
    // Section 3.3
    // The path may consist of a sequence of path segments separated by a
    // single slash "/" character.  Within a path segment, the characters
    // "/", ";", "=", and "?" are reserved.  Each path segment may include a
    // sequence of parameters, indicated by the semicolon ";" character.
    // The parameters are not significant to the parsing of relative
    // references.

    // The assertion that '?' is reserved in a path segment is inconsistent with
    // the grammar in that same section.
    // Parameters to path segments are not widely used, and ';' is a CSS special
    // character and a natural target for a badly written error recovery scheme.
    StringBuilder sb = new StringBuilder();
    // escape all but [\w\d.:+-]
    int pos = 0, n = path.length();
    for (int i = 0; i < n; ++i) {
      char ch = path.charAt(i);
      if (ch == '%') {
        if (isInvalidEsc(path, i)) {
          sb.append(path, pos, i).append("%25");
          pos = i + 1;
        }
      } else if (!(('a' <= ch && ch <= 'z')
            || ('A' <= ch && ch <= 'Z')
            || ('0' <= ch && ch <= '9')
            // Escapes ';' and '='
            || ch == ':' || ch == '-' || ch == '+'
            || ch == '.' || ch == '/' || ch == ',' || ch == '$')) {
        sb.append(path, pos, i);
        pos = i + 1;
        pctEncode(ch, sb);
      }
    }
    sb.append(path, pos, n);
    return sb.toString();
  }

  private static void normalizeQuery(String query, StringBuilder out) {
    // 3.4. Query Component
    // The query component is a string of information to be interpreted by
    // the resource.
    // Within a query component, the characters ";", "/", "?", ":", "@",
    // "&", "=", "+", ",", and "$" are reserved.

    // We preserve '&', '=', and the initial '?' but escape most others that are
    // CSS or JS special characters including ';', ':', and '@' for reasons
    // described above.

    int pos = 0, n = query.length();
    for (int i = 0; i < n; ++i) {
      char ch = query.charAt(i);
      if (ch == '%') {
        if (isInvalidEsc(query, i)) {
          out.append(query, pos, i).append("%25");
          pos = i + 1;
        }
      } else if (!(('a' <= ch && ch <= 'z')
            || ('A' <= ch && ch <= 'Z')
            || ('0' <= ch && ch <= '9')
            // Escapes ';', ':' and '@'
            || ch == '-' || ch == '+' || ch == '.' || ch == '=' || ch == '&'
            || ch == ',')) {
        out.append(query, pos, i);
        pos = i + 1;
        pctEncode(ch, out);
      }
    }
    out.append(query, pos, n);
  }

  private static void normalizeFragment(String fragment, StringBuilder out) {
    // Section 4.1
    // fragment      = *uric

    int pos = 0, n = fragment.length();
    for (int i = 0; i < n; ++i) {
      char ch = fragment.charAt(i);
      if (ch == '%') {
        if (isInvalidEsc(fragment, i)) {
          out.append(fragment, pos, i).append("%25");
          pos = i + 1;
        }
      } else if (!(('a' <= ch && ch <= 'z')
            || ('A' <= ch && ch <= 'Z')
            || ('0' <= ch && ch <= '9')
            || ch == '-' || ch == '+' || ch == '.')) {
        out.append(fragment, pos, i);
        pos = i + 1;
        pctEncode(ch, out);
      }
    }
    out.append(fragment, pos, n);
  }

  private static final Charset UTF8 = Charset.forName("UTF-8");
  private static void pctEncode(char ch, StringBuilder out) {
    if (ch < 0x80) {
      pctEncode((byte) ch, out);
    } else {
      // UTF-8 encode
      ByteBuffer bb = UTF8.encode(CharBuffer.wrap(new char[] { ch }));
      while (bb.position() < bb.limit()) {
        pctEncode(bb.get(), out);
      }
    }
  }
  private static void pctEncode(byte b, StringBuilder out) {
    out.append('%')
        .append("0123456789ABCDEF".charAt((b >> 4) & 0xf))
        .append("0123456789ABCDEF".charAt(b & 0xf));
  }

  private static boolean isInvalidEsc(String uriPart, int pctIdx) {
    return pctIdx + 2 >= uriPart.length()
        || !isHexDigit(uriPart.charAt(pctIdx + 1))
        || !isHexDigit(uriPart.charAt(pctIdx + 2));
  }

  private static boolean isHexDigit(char ch) {
    return ('0' <= ch && ch <= '9') || ('a' <= ch && ch <= 'f')
        || ('A' <= ch && ch <= 'F');
  }

  private UriUtil() { /* not instantiable */ }
}
