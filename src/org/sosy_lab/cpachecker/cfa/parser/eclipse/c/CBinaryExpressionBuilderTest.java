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
package org.sosy_lab.cpachecker.cfa.parser.eclipse.c;

import static com.google.common.truth.Truth.assertThat;
import static org.sosy_lab.cpachecker.cfa.ast.c.CBinaryExpression.BinaryOperator.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;
import org.sosy_lab.common.log.LogManager;
import org.sosy_lab.common.log.TestLogManager;
import org.sosy_lab.cpachecker.cfa.ast.c.CBinaryExpression.BinaryOperator;
import org.sosy_lab.cpachecker.cfa.ast.c.CIntegerLiteralExpression;
import org.sosy_lab.cpachecker.cfa.types.MachineModel;
import org.sosy_lab.cpachecker.cfa.types.c.CBasicType;
import org.sosy_lab.cpachecker.cfa.types.c.CSimpleType;
import org.sosy_lab.cpachecker.cfa.types.c.CType;

import com.google.common.collect.Lists;

@RunWith(Parameterized.class)
public class CBinaryExpressionBuilderTest {

  @Parameters(name="{0}")
  public static List<Object[]> getMachineModels() {
    List<Object[]> result = new ArrayList<>();
    for (MachineModel model : MachineModel.values()) {
      result.add(new Object[] { model });
    }
    return result;
  }

  @Parameter(0)
  public MachineModel machineModel;

  //type constants
  private final static CSimpleType S_CHAR =
      new CSimpleType(false, false, CBasicType.CHAR, false, false, true, false, false, false, false);
  private final static CSimpleType U_CHAR =
      new CSimpleType(false, false, CBasicType.CHAR, false, false, false, true, false, false, false);

  private final static CSimpleType S_SHORT_INT = makeInt(true, true, false, false);
  private final static CSimpleType U_SHORT_INT = makeInt(false, true, false, false);
  private final static CSimpleType S_INT = makeInt(true, false, false, false);
  private final static CSimpleType U_INT = makeInt(false, false, false, false);
  private final static CSimpleType S_LONG_INT = makeInt(true, false, true, false);
  private final static CSimpleType U_LONG_INT = makeInt(false, false, true, false);
  private final static CSimpleType S_LONG_LONG_INT = makeInt(true, false, false, true);
  private final static CSimpleType U_LONG_LONG_INT = makeInt(false, false, false, true);


  private final static List<CSimpleType> smallTypes = Lists.newArrayList(
      S_CHAR, U_CHAR, S_SHORT_INT, U_SHORT_INT, S_INT);
  private final static List<CSimpleType> bigTypes = Lists.newArrayList(
      U_INT, S_LONG_INT, U_LONG_INT, S_LONG_LONG_INT, U_LONG_LONG_INT);


  private static CSimpleType makeInt(
      boolean pIsSigned, boolean pIsShort, boolean pIsLong, boolean pIsLongLong) {
    return new CSimpleType(false, false, CBasicType.INT,
        pIsLong, pIsShort, pIsSigned, !pIsSigned, false, false, pIsLongLong);
  }


  private LogManager logger;

  private CBinaryExpressionBuilder c;


  @Before
  public void init() {
    logger = TestLogManager.getInstance();

    c = new CBinaryExpressionBuilder(machineModel, logger);
  }


  @Test
  public void checkTypeForBinaryOperation() {
    checkArithmeticTypes(PLUS);
    checkArithmeticTypes(MINUS);
    checkArithmeticTypes(MULTIPLY);

    checkArithmeticCalculationTypes(PLUS);
    checkArithmeticCalculationTypes(MINUS);
    checkArithmeticCalculationTypes(MULTIPLY);

    checkRelationalTypes(EQUALS);
    checkRelationalTypes(LESS_THAN);
    checkRelationalTypes(GREATER_EQUAL);

  }

  private void checkArithmeticTypes(BinaryOperator op) {

    for (CSimpleType small1 : smallTypes) {
      for (CSimpleType small2 : smallTypes) {
        checkResult(op, small1, small2, S_INT);
        checkCalculation(op, small1, small2, S_INT);
      }
    }

    for (CSimpleType big : bigTypes) {
      for (CSimpleType small : smallTypes) {
        checkResult(op, big, small, big);
        checkCalculation(op, big, small, big);
      }
    }
  }


  private void checkRelationalTypes(BinaryOperator op) {

    for (CSimpleType small1 : smallTypes) {
      for (CSimpleType small2 : smallTypes) {
        checkResult(op, small1, small2, S_INT);
        checkCalculation(op, small1, small2, S_INT);
      }
    }

    for (CSimpleType big : bigTypes) {
      for (CSimpleType small : smallTypes) {
        checkResult(op, big, small, S_INT);
        checkCalculation(op, big, small, big);
      }
      for (CSimpleType big2 : bigTypes) {
        checkResult(op, big, big2, S_INT);
      }
    }
  }


  private void checkArithmeticCalculationTypes(BinaryOperator op) {
    checkCalculation(op, U_INT, U_INT, U_INT);
    if (machineModel == MachineModel.LINUX32) {
      checkCalculation(op, U_INT, S_LONG_INT, U_LONG_INT); // !!!!
    } else {
      checkCalculation(op, U_INT, S_LONG_INT, S_LONG_INT);
    }
    checkCalculation(op, U_INT, U_LONG_INT, U_LONG_INT);
    checkCalculation(op, U_INT, S_LONG_LONG_INT, S_LONG_LONG_INT);
    checkCalculation(op, U_INT, U_LONG_LONG_INT, U_LONG_LONG_INT);

    checkCalculation(op, S_LONG_INT, S_LONG_INT, S_LONG_INT);
    checkCalculation(op, S_LONG_INT, U_LONG_INT, U_LONG_INT);
    checkCalculation(op, S_LONG_INT, S_LONG_LONG_INT, S_LONG_LONG_INT);
    checkCalculation(op, S_LONG_INT, U_LONG_LONG_INT, U_LONG_LONG_INT);

    checkCalculation(op, U_LONG_INT, U_LONG_INT, U_LONG_INT);
    if (machineModel == MachineModel.LINUX64) {
      checkCalculation(op, U_LONG_INT, S_LONG_LONG_INT, U_LONG_LONG_INT); // !!!!
    } else {
      checkCalculation(op, U_LONG_INT, S_LONG_LONG_INT, S_LONG_LONG_INT);
    }
    checkCalculation(op, U_LONG_INT, U_LONG_LONG_INT, U_LONG_LONG_INT);

    checkCalculation(op, S_LONG_LONG_INT, S_LONG_LONG_INT, S_LONG_LONG_INT);
    checkCalculation(op, S_LONG_LONG_INT, U_LONG_LONG_INT, U_LONG_LONG_INT);

    checkCalculation(op, U_LONG_LONG_INT, U_LONG_LONG_INT, U_LONG_LONG_INT);
  }

  private void checkCalculation(BinaryOperator op, CType t1, CType t2, CType target) {
    assertThat(c.getCalculationTypeForBinaryOperation(t1, t2, op, CIntegerLiteralExpression.ZERO, CIntegerLiteralExpression.ZERO))
              .isEqualTo(target);
    assertThat(c.getCalculationTypeForBinaryOperation(t2, t1, op, CIntegerLiteralExpression.ZERO, CIntegerLiteralExpression.ZERO))
              .isEqualTo(target);
  }

  private void checkResult(BinaryOperator op, CType t1, CType t2, CType target) {
    assertThat(c.getResultTypeForBinaryOperation(t1, t2, op, CIntegerLiteralExpression.ZERO, CIntegerLiteralExpression.ZERO))
              .isEqualTo(target);
    assertThat(c.getResultTypeForBinaryOperation(t2, t1, op, CIntegerLiteralExpression.ZERO, CIntegerLiteralExpression.ZERO))
              .isEqualTo(target);
  }
}
