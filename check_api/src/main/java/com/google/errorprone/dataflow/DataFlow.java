/*
 * Copyright 2014 The Error Prone Authors.
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

package com.google.errorprone.dataflow;

import com.google.auto.value.AutoValue;
import com.google.common.base.Preconditions;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.util.concurrent.UncheckedExecutionException;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import com.sun.tools.javac.code.Symbol.CompletionFailure;
import com.sun.tools.javac.processing.JavacProcessingEnvironment;
import com.sun.tools.javac.util.Context;
import javax.annotation.Nullable;
import javax.annotation.processing.ProcessingEnvironment;

import org.checkerframework.shaded.dataflow.analysis.AbstractValue;
import org.checkerframework.shaded.dataflow.analysis.ForwardAnalysis;
import org.checkerframework.shaded.dataflow.analysis.ForwardAnalysisImpl;
import org.checkerframework.shaded.dataflow.analysis.ForwardTransferFunction;
import org.checkerframework.shaded.dataflow.analysis.Store;
import org.checkerframework.shaded.dataflow.cfg.CFGBuilder;
import org.checkerframework.shaded.dataflow.cfg.ControlFlowGraph;
import org.checkerframework.shaded.dataflow.cfg.UnderlyingAST;

/**
 * Provides a wrapper around {@link org.checkerframework.shaded.dataflow.analysis.ForwardAnalysisImpl}.
 *
 * @author konne@google.com (Konstantin Weitz)
 */
public final class DataFlow {

  /** A pair of Analysis and ControlFlowGraph. */
  public interface Result<
      A extends AbstractValue<A>, S extends Store<S>, T extends ForwardTransferFunction<A, S>> {
    ForwardAnalysisImpl<A, S, T> getAnalysis();

    ControlFlowGraph getControlFlowGraph();
  }

  /*
   * We cache both the control flow graph and the analyses that are run on it.
   * We tuned performance to the following assumptions (which are currently true for error-prone):
   *
   * <ul>
   * <li> all dataflow analyses for a method are finished before another method is analyzed
   * <li> multiple dataflow analyses for the same method are executed in arbitrary order
   * </ul>
   *
   * TODO(b/158869538): Write a test that checks these assumptions
   */
  private static final LoadingCache<AnalysisParams, ForwardAnalysis<?, ?, ?>> analysisCache =
      CacheBuilder.newBuilder()
          .build(
              new CacheLoader<AnalysisParams, ForwardAnalysis<?, ?, ?>>() {
                @Override
                public ForwardAnalysis<?, ?, ?> load(AnalysisParams key) {
                  final ControlFlowGraph cfg = key.cfg();
                  final ForwardTransferFunction<?, ?> transfer = key.transferFunction();

                  final ForwardAnalysis<?, ?, ?> analysis = new ForwardAnalysisImpl<>(transfer);
                  analysis.performAnalysis(cfg);
                  return analysis;
                }
              });

  private static final LoadingCache<CfgParams, ControlFlowGraph> cfgCache =
      CacheBuilder.newBuilder()
          .maximumSize(1)
          .build(
              new CacheLoader<CfgParams, ControlFlowGraph>() {
                @Override
                public ControlFlowGraph load(CfgParams key) {
                  final TreePath methodPath = key.methodPath();
                  final UnderlyingAST ast;
                  ClassTree classTree = null;
                  for (Tree parent : methodPath) {
                    if (parent instanceof ClassTree) {
                      classTree = (ClassTree) parent;
                      break;
                    }
                  }
                  if (methodPath.getLeaf() instanceof LambdaExpressionTree) {
                    ast = new UnderlyingAST.CFGLambda((LambdaExpressionTree) methodPath.getLeaf());
                  } else if (methodPath.getLeaf() instanceof MethodTree) {
                    MethodTree method = (MethodTree) methodPath.getLeaf();
                    ast = new UnderlyingAST.CFGMethod(method, classTree);
                  } else {
                    // must be an initializer per findEnclosingMethodOrLambdaOrInitializer
                    ast = new UnderlyingAST.CFGStatement(methodPath.getLeaf(), classTree);
                  }
                  final ProcessingEnvironment env = key.environment();

                  analysisCache.invalidateAll();
                  CompilationUnitTree root = methodPath.getCompilationUnit();
                  // TODO(b/158869538): replace with faster build(bodyPath, env, ast, false, false);
                  return CFGBuilder.build(root, ast, false, false, env);
                }
              });

  // TODO(b/158869538): remove once we merge jdk8 specific's with core
  @Nullable
  private static <T> TreePath findEnclosingMethodOrLambdaOrInitializer(TreePath path) {
    while (path != null) {
      if (path.getLeaf() instanceof MethodTree) {
        return path;
      }
      TreePath parent = path.getParentPath();
      if (parent != null) {
        if (parent.getLeaf() instanceof ClassTree) {
          if (path.getLeaf() instanceof BlockTree) {
            // this is a class or instance initializer block
            return path;
          }
          if (path.getLeaf() instanceof VariableTree
              && ((VariableTree) path.getLeaf()).getInitializer() != null) {
            // this is a field with an inline initializer
            return path;
          }
        }
        if (parent.getLeaf() instanceof LambdaExpressionTree) {
          return parent;
        }
      }
      path = parent;
    }
    return null;
  }

  /**
   * Run the {@code transfer} dataflow analysis over the method or lambda which is the leaf of the
   * {@code methodPath}.
   *
   * <p>For caching, we make the following assumptions: - if two paths to methods are {@code equal},
   * their control flow graph is the same. - if two transfer functions are {@code equal}, and are
   * run over the same control flow graph, the analysis result is the same. - for all contexts, the
   * analysis result is the same.
   */
  private static <A extends AbstractValue<A>, S extends Store<S>, T extends ForwardTransferFunction<A, S>>
      Result<A, S, T> methodDataflow(TreePath methodPath, Context context, T transfer) {
    final ProcessingEnvironment env = JavacProcessingEnvironment.instance(context);

    final ControlFlowGraph cfg;
    try {
      cfg = cfgCache.getUnchecked(CfgParams.create(methodPath, env));
    } catch (UncheckedExecutionException e) {
      throw e.getCause() instanceof CompletionFailure ? (CompletionFailure) e.getCause() : e;
    }
    final AnalysisParams aparams = AnalysisParams.create(transfer, cfg, env);
    @SuppressWarnings("unchecked")
    final ForwardAnalysisImpl<A, S, T> analysis = (ForwardAnalysisImpl<A, S, T>) analysisCache.getUnchecked(aparams);

    return new Result<A, S, T>() {
      @Override
      public ForwardAnalysisImpl<A, S, T> getAnalysis() {
        return analysis;
      }

      @Override
      public ControlFlowGraph getControlFlowGraph() {
        return cfg;
      }
    };
  }

  /**
   * Runs the {@code transfer} dataflow analysis to compute the abstract value of the expression
   * which is the leaf of {@code exprPath}.
   *
   * <p>The expression must be part of a method, lambda, or initializer (inline field initializer or
   * initializer block). Example of an expression outside of such constructs is the identifier in an
   * import statement.
   *
   * <p>Note that for intializers, each inline field initializer or initializer block is treated
   * separately. I.e., we don't merge all initializers into one virtual block for dataflow.
   *
   * @return dataflow result for the given expression or {@code null} if the expression is not part
   *     of a method, lambda or initializer
   */
  @Nullable
  public static <A extends AbstractValue<A>, S extends Store<S>, T extends ForwardTransferFunction<A, S>>
      A expressionDataflow(TreePath exprPath, Context context, T transfer) {
    final Tree leaf = exprPath.getLeaf();
    Preconditions.checkArgument(
        leaf instanceof ExpressionTree,
        "Leaf of exprPath must be of type ExpressionTree, but was %s",
        leaf.getClass().getName());

    final ExpressionTree expr = (ExpressionTree) leaf;
    final TreePath enclosingMethodPath = findEnclosingMethodOrLambdaOrInitializer(exprPath);
    if (enclosingMethodPath == null) {
      // expression is not part of a method, lambda, or initializer
      return null;
    }

    final Tree method = enclosingMethodPath.getLeaf();
    if (method instanceof MethodTree && ((MethodTree) method).getBody() == null) {
      // expressions can occur in abstract methods, for example {@code Map.Entry} in:
      //
      //   abstract Set<Map.Entry<K, V>> entries();
      return null;
    }

    return methodDataflow(enclosingMethodPath, context, transfer).getAnalysis().getValue(expr);
  }

  @AutoValue
  abstract static class CfgParams {
    abstract TreePath methodPath();

    // Should not be used for hashCode or equals
    private ProcessingEnvironment environment;

    private static CfgParams create(TreePath methodPath, ProcessingEnvironment environment) {
      CfgParams cp = new AutoValue_DataFlow_CfgParams(methodPath);
      cp.environment = environment;
      return cp;
    }

    ProcessingEnvironment environment() {
      return environment;
    }
  }

  @AutoValue
  abstract static class AnalysisParams {

    abstract ForwardTransferFunction<?, ?> transferFunction();

    abstract ControlFlowGraph cfg();

    // Should not be used for hashCode or equals
    private ProcessingEnvironment environment;

    private static AnalysisParams create(
        ForwardTransferFunction<?, ?> transferFunction,
        ControlFlowGraph cfg,
        ProcessingEnvironment environment) {
      AnalysisParams ap = new AutoValue_DataFlow_AnalysisParams(transferFunction, cfg);
      ap.environment = environment;
      return ap;
    }

    ProcessingEnvironment environment() {
      return environment;
    }
  }
}
