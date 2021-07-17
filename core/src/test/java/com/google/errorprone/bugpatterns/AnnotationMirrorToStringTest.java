/*
 * Copyright 2021 The Error Prone Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.errorprone.bugpatterns;

import com.google.errorprone.BugCheckerRefactoringTestHelper;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** {@link AnnotationMirrorToString}Test */
@RunWith(JUnit4.class)
public class AnnotationMirrorToStringTest {

  private final BugCheckerRefactoringTestHelper testHelper =
      BugCheckerRefactoringTestHelper.newInstance(AnnotationMirrorToString.class, getClass());

  @Test
  public void refactoring() {
    testHelper
        .addInputLines(
            "Test.java",
            "import javax.lang.model.element.AnnotationMirror;",
            "class Test {",
            "  String f(AnnotationMirror av) {",
            "    return av.toString();",
            "  }",
            "}")
        .addOutputLines(
            "Test.java",
            "import com.google.auto.common.AnnotationMirrors;",
            "import javax.lang.model.element.AnnotationMirror;",
            "class Test {",
            "  String f(AnnotationMirror av) {",
            "    return AnnotationMirrors.toString(av);",
            "  }",
            "}")
        .allowBreakingChanges() // TODO(cushon): remove after the next auto-common release
        .doTest();
  }
}
