/*
 *  CPAchecker is a tool for configurable software verification.
 *  This file is part of CPAchecker.
 *
 *  Copyright (C) 2007-2014  Dirk Beyer
 *  All rights reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *
 *  CPAchecker web page:
 *    http://cpachecker.sosy-lab.org
 */
package org.sosy_lab.cpachecker.cpa.arg.counterexamples;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.base.Joiner;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import javax.annotation.Nullable;
import org.sosy_lab.common.Appender;
import org.sosy_lab.common.Appenders;
import org.sosy_lab.common.configuration.ClassOption;
import org.sosy_lab.common.configuration.Configuration;
import org.sosy_lab.common.configuration.InvalidConfigurationException;
import org.sosy_lab.common.configuration.Option;
import org.sosy_lab.common.configuration.Options;
import org.sosy_lab.common.io.IO;
import org.sosy_lab.common.io.PathTemplate;
import org.sosy_lab.common.log.LogManager;
import org.sosy_lab.cpachecker.cfa.CFA;
import org.sosy_lab.cpachecker.cfa.model.CFAEdge;
import org.sosy_lab.cpachecker.core.counterexample.CounterexampleInfo;
import org.sosy_lab.cpachecker.core.interfaces.ConfigurableProgramAnalysis;
import org.sosy_lab.cpachecker.cpa.arg.ARGState;
import org.sosy_lab.cpachecker.cpa.arg.ARGToDotWriter;
import org.sosy_lab.cpachecker.cpa.arg.ARGUtils;
import org.sosy_lab.cpachecker.cpa.arg.ErrorPathShrinker;
import org.sosy_lab.cpachecker.cpa.arg.path.ARGPath;
import org.sosy_lab.cpachecker.cpa.arg.witnessexport.ExtendedWitnessExporter;
import org.sosy_lab.cpachecker.cpa.arg.witnessexport.WitnessExporter;
import org.sosy_lab.cpachecker.util.Pair;
import org.sosy_lab.cpachecker.util.coverage.CoverageCollector;
import org.sosy_lab.cpachecker.util.coverage.CoverageReportGcov;
import org.sosy_lab.cpachecker.util.cwriter.PathToCTranslator;
import org.sosy_lab.cpachecker.util.cwriter.PathToConcreteProgramTranslator;
import org.sosy_lab.cpachecker.util.harness.HarnessExporter;

@Options(prefix="counterexample.export", deprecatedPrefix="cpa.arg.errorPath")
public class CEXExporter {

  enum CounterexampleExportType {
    CBMC, CONCRETE_EXECUTION;
  }

  @Option(
    secure = true,
    name = "compressWitness",
    description = "compress the produced error-witness automata using GZIP compression."
  )
  private boolean compressWitness = true;

  @Option(secure=true, name="codeStyle",
          description="exports either CMBC format or a concrete path program")
  private CounterexampleExportType codeStyle = CounterexampleExportType.CBMC;

  @Option(
    secure = true,
    name = "filters",
    description =
        "Filter for irrelevant counterexamples to reduce the number of similar counterexamples reported."
            + " Only relevant with analysis.stopAfterError=false and counterexample.export.exportImmediately=true."
            + " Put the weakest and cheapest filter first, e.g., PathEqualityCounterexampleFilter."
  )
  @ClassOption(packagePrefix = "org.sosy_lab.cpachecker.cpa.arg.counterexamples")
  private List<CounterexampleFilter.Factory> cexFilterClasses =
      ImmutableList.of(PathEqualityCounterexampleFilter::new);

  private final CounterexampleFilter cexFilter;

  private final CEXExportOptions options;
  private final LogManager logger;
  private final WitnessExporter witnessExporter;
  private final ExtendedWitnessExporter extendedWitnessExporter;
  private final HarnessExporter harnessExporter;

  public CEXExporter(
      Configuration config,
      CEXExportOptions pOptions,
      LogManager pLogger,
      CFA cfa,
      ConfigurableProgramAnalysis cpa,
      WitnessExporter pWitnessExporter,
      ExtendedWitnessExporter pExtendedWitnessExporter)
      throws InvalidConfigurationException {
    config.inject(this);
    options = pOptions;
    logger = pLogger;
    witnessExporter = checkNotNull(pWitnessExporter);
    extendedWitnessExporter = checkNotNull(pExtendedWitnessExporter);

    if (!options.disabledCompletely()) {
      cexFilter =
          CounterexampleFilter.createCounterexampleFilter(config, pLogger, cpa, cexFilterClasses);
      harnessExporter = new HarnessExporter(config, pLogger, cfa);
    } else {
      cexFilter = null;
      harnessExporter = null;
    }
  }

  /** @see #exportCounterexample(ARGState, CounterexampleInfo) */
  public void exportCounterexampleIfRelevant(
      final ARGState pTargetState, final CounterexampleInfo pCounterexampleInfo)
      throws InterruptedException {
    if (options.disabledCompletely()) {
      return;
    }

    if (cexFilter.isRelevant(pCounterexampleInfo)) {
      exportCounterexample(pTargetState, pCounterexampleInfo);
    } else {
      logger.log(
          Level.FINEST,
          "Skipping counterexample printing because it is similar to one of already printed.");
    }
  }

  /**
   * Export an Error Trace in different formats, for example as C-file, dot-file or automaton.
   *
   * @param targetState state of an ARG, used as fallback, if pCounterexampleInfo contains no
   *     targetPath.
   * @param counterexample contains further information and the (optional) targetPath. If the
   *     targetPath is available, it will be used for the output. Otherwise we use backwards
   *     reachable states from pTargetState.
   */
  public void exportCounterexample(
      final ARGState targetState, final CounterexampleInfo counterexample) {
    checkNotNull(targetState);
    checkNotNull(counterexample);
    if (options.disabledCompletely()) {
      return;
    }

    final ARGPath targetPath = counterexample.getTargetPath();
    final Predicate<Pair<ARGState, ARGState>> isTargetPathEdge = Predicates.in(
        new HashSet<>(targetPath.getStatePairs()));
    final ARGState rootState = targetPath.getFirstState();
    final int uniqueId = counterexample.getUniqueId();

    if (options.getCoveragePrefix() != null) {
      Path outputPath = options.getCoveragePrefix().getPath(counterexample.getUniqueId());
      try (Writer gcovFile = IO.openOutputFile(outputPath, Charset.defaultCharset())) {
        CoverageReportGcov.write(CoverageCollector.fromCounterexample(targetPath), gcovFile);
      } catch (IOException e) {
        logger.logUserException(
            Level.WARNING, e, "Could not write coverage information for counterexample to file");
      }
    }

    writeErrorPathFile(options.getErrorPathFile(), uniqueId, counterexample);

    if (options.getCoreFile() != null) {
      // the shrinked errorPath only includes the nodes,
      // that are important for the error, it is not a complete path,
      // only some nodes of the targetPath are part of it
      ErrorPathShrinker pathShrinker = new ErrorPathShrinker();
      List<CFAEdge> shrinkedErrorPath = pathShrinker.shrinkErrorPath(targetPath);
      writeErrorPathFile(
          options.getCoreFile(),
          uniqueId,
          Appenders.forIterable(Joiner.on('\n'), shrinkedErrorPath));
    }

    final Set<ARGState> pathElements;
    Appender pathProgram = null;
    if (counterexample.isPreciseCounterExample()) {
      pathElements = targetPath.getStateSet();

      if (options.getSourceFile() != null) {
        switch(codeStyle) {
        case CONCRETE_EXECUTION:
          pathProgram = PathToConcreteProgramTranslator.translateSinglePath(targetPath, counterexample.getCFAPathWithAssignments());
          break;
        case CBMC:
          pathProgram = PathToCTranslator.translateSinglePath(targetPath);
          break;
        default:
          throw new AssertionError("Unhandled case statement: " + codeStyle);
        }
      }

    } else {
      // Imprecise error path.
      // For the text export, we have no other chance,
      // but for the C code and graph export we use all existing paths
      // to avoid this problem.
      pathElements = ARGUtils.getAllStatesOnPathsTo(targetState);

      if (options.getSourceFile() != null) {
        switch(codeStyle) {
        case CONCRETE_EXECUTION:
          logger.log(Level.WARNING, "Cannot export imprecise counterexample to C code for concrete execution.");
          break;
        case CBMC:
          // "translatePaths" does not work if the ARG branches without assume edge
          if (ARGUtils.hasAmbiguousBranching(rootState, pathElements)) {
            pathProgram = PathToCTranslator.translateSinglePath(targetPath);
          } else {
            pathProgram = PathToCTranslator.translatePaths(rootState, pathElements);
          }
          break;
        default:
          throw new AssertionError("Unhandled case statement: " + codeStyle);
        }
      }
    }

    if (pathProgram != null) {
      writeErrorPathFile(options.getSourceFile(), uniqueId, pathProgram);
    }

    writeErrorPathFile(
        options.getGraphFile(),
        uniqueId,
        (Appender)
            pAppendable ->
                ARGToDotWriter.write(
                    pAppendable,
                    rootState,
                    ARGState::getChildren,
                    Predicates.in(pathElements),
                    isTargetPathEdge));

    writeErrorPathFile(
        options.getAutomatonFile(),
        uniqueId,
        (Appender)
            pAppendable ->
                ARGUtils.producePathAutomaton(
                    pAppendable, rootState, pathElements, "ErrorPath" + uniqueId, counterexample));

    for (Pair<Object, PathTemplate> info : counterexample.getAllFurtherInformation()) {
      if (info.getSecond() != null) {
        writeErrorPathFile(info.getSecond(), uniqueId, info.getFirst());
      }
    }

    writeErrorPathFile(
        options.getWitnessFile(),
        uniqueId,
        (Appender)
            pAppendable ->
                witnessExporter.writeErrorWitness(
                    pAppendable,
                    rootState,
                    Predicates.in(pathElements),
                    isTargetPathEdge,
                    counterexample),
        compressWitness);

    writeErrorPathFile(
        options.getExtendedWitnessFile(),
        uniqueId,
        (Appender)
            pAppendable ->
                extendedWitnessExporter.writeErrorWitness(
                    pAppendable,
                    rootState,
                    Predicates.in(pathElements),
                    isTargetPathEdge,
                    counterexample),
        compressWitness);

    writeErrorPathFile(
        options.getTestHarnessFile(),
        uniqueId,
        (Appender)
            pAppendable ->
                harnessExporter.writeHarness(
                    pAppendable,
                    rootState,
                    Predicates.in(pathElements),
                    isTargetPathEdge,
                    counterexample));
  }

  // Copied from org.sosy_lab.cpachecker.util.coverage.FileCoverageInformation.addVisitedLine(int)
  public void addVisitedLine(Map<Integer,Integer> visitedLines, int pLine) {
    checkArgument(pLine > 0);
    if (visitedLines.containsKey(pLine)) {
      visitedLines.put(pLine, visitedLines.get(pLine) + 1);
    } else {
      visitedLines.put(pLine, 1);
    }
  }

  private void writeErrorPathFile(@Nullable PathTemplate template, int uniqueId, Object content) {
    writeErrorPathFile(template, uniqueId, content, false);
  }

  private void writeErrorPathFile(
      @Nullable PathTemplate template, int uniqueId, Object content, boolean pCompress) {
    if (template != null) {
      // fill in index in file name
      Path file = template.getPath(uniqueId);

      try {
        if (!pCompress) {
          IO.writeFile(file, Charset.defaultCharset(), content);
        } else {
          file = file.resolveSibling(file.getFileName() + ".gz");
          IO.writeGZIPFile(file, Charset.defaultCharset(), content);
        }
      } catch (IOException e) {
        logger.logUserException(Level.WARNING, e,
                "Could not write information about the error path to file");
      }
    }
  }
}
