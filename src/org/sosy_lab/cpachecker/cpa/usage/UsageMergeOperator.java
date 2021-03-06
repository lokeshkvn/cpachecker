/*
 *  CPAchecker is a tool for configurable software verification.
 *  This file is part of CPAchecker.
 *
 *  Copyright (C) 2007-2018  Dirk Beyer
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
package org.sosy_lab.cpachecker.cpa.usage;

import org.sosy_lab.cpachecker.core.interfaces.AbstractState;
import org.sosy_lab.cpachecker.core.interfaces.MergeOperator;
import org.sosy_lab.cpachecker.core.interfaces.Precision;
import org.sosy_lab.cpachecker.exceptions.CPAException;

public class UsageMergeOperator implements MergeOperator {

  private final MergeOperator wrappedMerge;

  public UsageMergeOperator(MergeOperator wrapped) {
    wrappedMerge = wrapped;
  }

  @Override
  public AbstractState merge(AbstractState pState1, AbstractState pState2, Precision pPrecision)
      throws CPAException, InterruptedException {

    UsageState uState1 = (UsageState) pState1;
    UsageState uState2 = (UsageState) pState2;
    UsagePrecision prec = (UsagePrecision) pPrecision;

    AbstractState wrappedState1 = uState1.getWrappedState();
    AbstractState wrappedState2 = uState2.getWrappedState();

    AbstractState mergedState =
        wrappedMerge.merge(wrappedState1, wrappedState2, prec.getWrappedPrecision());

    UsageState result;

    if (uState1.isLessOrEqual(uState2)) {
      result = uState2.copy(mergedState);
    } else if (uState2.isLessOrEqual(uState1)) {
      result = uState1.copy(mergedState);
    } else {
      result = uState1.copy(mergedState);
      result.joinRecentUsagesFrom(uState2);
    }

    if (mergedState.equals(wrappedState2) && result.equals(uState2)) {
      return pState2;
    } else {
      return result;
    }
  }
}
