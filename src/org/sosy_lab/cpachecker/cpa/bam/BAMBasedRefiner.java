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
package org.sosy_lab.cpachecker.cpa.bam;

import static com.google.common.base.Preconditions.checkArgument;

import com.google.common.base.Preconditions;
import org.sosy_lab.common.configuration.InvalidConfigurationException;
import org.sosy_lab.common.log.LogManager;
import org.sosy_lab.cpachecker.core.counterexample.CounterexampleInfo;
import org.sosy_lab.cpachecker.core.interfaces.ConfigurableProgramAnalysis;
import org.sosy_lab.cpachecker.core.interfaces.Refiner;
import org.sosy_lab.cpachecker.cpa.arg.ARGBasedRefiner;
import org.sosy_lab.cpachecker.cpa.arg.ARGCPA;
import org.sosy_lab.cpachecker.cpa.arg.ARGPath;
import org.sosy_lab.cpachecker.cpa.arg.ARGReachedSet;
import org.sosy_lab.cpachecker.cpa.arg.ARGState;
import org.sosy_lab.cpachecker.cpa.arg.ARGUtils;
import org.sosy_lab.cpachecker.cpa.arg.AbstractARGBasedRefiner;
import org.sosy_lab.cpachecker.cpa.bam.BAMSubgraphComputer.BackwardARGState;
import org.sosy_lab.cpachecker.exceptions.CPAException;
import org.sosy_lab.cpachecker.exceptions.CPATransferException;
import org.sosy_lab.cpachecker.util.Pair;
import org.sosy_lab.cpachecker.util.statistics.StatTimer;

/**
 * This is an extension of {@link AbstractARGBasedRefiner} that takes care of
 * flattening the ARG before calling
 * {@link ARGBasedRefiner#performRefinementForPath(ARGReachedSet, ARGPath)}.
 *
 * Warning: Although the ARG is flattened at this point, the elements in it have
 * not been expanded due to performance reasons.
 */
public final class BAMBasedRefiner extends AbstractARGBasedRefiner {

  final StatTimer computePathTimer = new StatTimer("Compute path for refinement");
  final StatTimer computeSubtreeTimer = new StatTimer("Constructing flat ARG");
  final StatTimer computeCounterexampleTimer = new StatTimer("Searching path to error location");
  final StatTimer removeCachedSubtreeTimer = new StatTimer("Removing cached subtrees");

  private final AbstractBAMCPA bamCpa;

  private BAMBasedRefiner(
      ARGBasedRefiner pRefiner, ARGCPA pArgCpa, AbstractBAMCPA pBamCpa, LogManager pLogger) {
    super(pRefiner, pArgCpa, pLogger);

    bamCpa = pBamCpa;
    bamCpa.getStatistics().addRefiner(this);
  }

  /**
   * Create a {@link Refiner} instance that supports BAM from a {@link ARGBasedRefiner} instance.
   */
  public static Refiner forARGBasedRefiner(
      final ARGBasedRefiner pRefiner, final ConfigurableProgramAnalysis pCpa)
      throws InvalidConfigurationException {
    checkArgument(
        !(pRefiner instanceof Refiner),
        "ARGBasedRefiners may not implement Refiner, choose between these two!");

    if (!(pCpa instanceof AbstractBAMCPA)) {
      throw new InvalidConfigurationException("BAM CPA needed for BAM-based refinement");
    }
    AbstractBAMCPA bamCpa = (AbstractBAMCPA) pCpa;
    ARGCPA argCpa = bamCpa.retrieveWrappedCpa(ARGCPA.class);
    if (argCpa == null) {
      throw new InvalidConfigurationException("ARG CPA needed for refinement");
    }
    return new BAMBasedRefiner(pRefiner, argCpa, bamCpa, bamCpa.getLogger());
  }

  @Override
  protected final CounterexampleInfo performRefinementForPath(
      ARGReachedSet pReached, ARGPath pPath) throws CPAException, InterruptedException {
    checkArgument(!(pReached instanceof BAMReachedSet),
        "Wrapping of BAM-based refiners inside BAM-based refiners is not allowed.");
    Preconditions.checkNotNull(pPath);
    Preconditions.checkArgument(pPath.size() > 0);

    // wrap the original reached-set to have a valid "view" on all reached states.
    return super.performRefinementForPath(
        new BAMReachedSet(bamCpa, pReached, pPath, removeCachedSubtreeTimer),
        pPath);
  }

  @Override
  protected final ARGPath computePath(
      ARGState pLastElement, ARGReachedSet pMainReachedSet) throws InterruptedException, CPATransferException {
    assert pLastElement.isTarget();
    assert pMainReachedSet.asReachedSet().contains(pLastElement) : "targetState must be in mainReachedSet.";

    computePathTimer.start();
    try {
      computeSubtreeTimer.start();
      Pair<BackwardARGState, BackwardARGState> rootAndTargetOfSubgraph;
      try {
        final BAMSubgraphComputer cexSubgraphComputer = new BAMSubgraphComputer(bamCpa.getData());
        rootAndTargetOfSubgraph = Preconditions.checkNotNull(
                cexSubgraphComputer.computeCounterexampleSubgraph(pLastElement, pMainReachedSet));
      } finally {
        computeSubtreeTimer.stop();
      }

      computeCounterexampleTimer.start();
      try {
        // search path to target, as in super-class
        return ARGUtils.getOnePathTo(rootAndTargetOfSubgraph.getSecond());
      } finally {
        computeCounterexampleTimer.stop();
      }
    } finally {
      computePathTimer.stop();
    }
  }
}
