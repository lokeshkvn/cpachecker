/*
 * CPAchecker is a tool for configurable software verification.
 *  This file is part of CPAchecker.
 *
 *  Copyright (C) 2007-2017  Dirk Beyer
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
package org.sosy_lab.cpachecker.cfa.parser.llvm;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.stream.Collectors;
import org.llvm.Module;
import org.sosy_lab.common.log.LogManager;
import org.sosy_lab.common.time.Timer;
import org.sosy_lab.cpachecker.cfa.ParseResult;
import org.sosy_lab.cpachecker.cfa.Parser;
import org.sosy_lab.cpachecker.cfa.types.MachineModel;
import org.sosy_lab.cpachecker.exceptions.LLVMParserException;
import org.sosy_lab.cpachecker.exceptions.ParserException;

/**
 * Parser for the LLVM intermediate language to a CFA.
 * LLVM IR is a typed, assembler-like language that uses the SSA form by default.
 * Because of this, parsing is quite simple: there is no need for scoping
 * and expression trees are always flat.
 */
public class LlvmParser implements Parser {

  private final LogManager logger;
  private final CFABuilder cfaBuilder;

  private final Timer parseTimer = new Timer();
  private final Timer cfaCreationTimer = new Timer();

  public LlvmParser(
      final LogManager pLogger,
      final MachineModel pMachineModel
  ) {
    logger = pLogger;
    cfaBuilder = new CFABuilder(logger, pMachineModel);
  }

  @Override
  public ParseResult parseFile(final String pFilename)
      throws ParserException, IOException, InterruptedException {
    Module llvmModule;
    parseTimer.start();
    try {
      addLlvmLookupDirs();
      llvmModule = Module.parseIR(pFilename);
    } finally {
      parseTimer.stop();
    }

    if (llvmModule == null) {
      throw new LLVMParserException("Error while parsing");
    }
    // TODO: Handle/show errors in parser

    return buildCfa(llvmModule, pFilename);
  }

  private void addLlvmLookupDirs() {
    List<Path> libDirs = new ArrayList<>(3);
    try {
      String encodedBasePath =
          LlvmParser.class.getProtectionDomain().getCodeSource().getLocation().getPath();
      String decodedBasePath = URLDecoder.decode(encodedBasePath, "UTF-8");

      // If cpachecker.jar is used, decodedBasePath will look similar to CPACHECKER/cpachecker.jar .
      // If the compiled class files are used outside of a jar, decodedBasePath will look similar to
      // CPACHECKER/bin .
      // In both cases, we strip the last part to get the CPAchecker base directory.
      Path cpacheckerDir = Paths.get(decodedBasePath).getParent();
      Path runtimeLibDir = Paths.get(cpacheckerDir.toString(), "lib", "java", "runtime");
      libDirs.add(runtimeLibDir);

      String osName = System.getProperty("os.name");
      String osArch = System.getProperty("os.arch");
      Predicate<Path> isRelevantDir = new FitsMachine(osName, osArch);
      Path nativeDir = Paths.get(cpacheckerDir.toString(), "lib", "native");

      List<Path> nativeLibCandidates = Files.walk(nativeDir, 1, FileVisitOption.FOLLOW_LINKS)
          .filter(isRelevantDir)
          .collect(Collectors.toList());
      libDirs.addAll(nativeLibCandidates);


    } catch (UnsupportedEncodingException e) {
      throw new AssertionError(e);
    } catch (IOException e) {
      logger.log(Level.INFO,
          "IOException occurred, but trying to continue. Message: %s", e.getMessage());
      // Ignore and try to resolve libraries with existing directories
    }

    for (Path p : libDirs) {
      logger.log(Level.FINE, "Adding llvm shared library lookup dir: %s", p);
    }
    Module.addLibraryLookupPaths(libDirs);
  }

  private ParseResult buildCfa(final Module pModule, final String pFilename) {
    return cfaBuilder.build(pModule, pFilename);
  }

  @Override
  public ParseResult parseString(final String pFilename, final String pCode)
      throws ParserException {
    return null;
  }

  @Override
  public Timer getParseTime() {
    return parseTimer;
  }

  @Override
  public Timer getCFAConstructionTime() {
    return cfaCreationTimer;
  }

  private static class FitsMachine implements Predicate<Path> {

    private final String os;
    private final String arch;

    public FitsMachine(final String pOs, final String pArchitecture) {
      String canonicOsName = pOs.toLowerCase();
      if (canonicOsName.contains("mac")) {
        os = "mac";
      } else if (canonicOsName.contains("win")) {
        os = "win";
      } else if (canonicOsName.contains("bsd")) {
        os = "bsd";
      } else {
        assert canonicOsName.contains("linux");
        os = "linux";
      }

      if (pArchitecture.contains("32")) {
        arch = "32";
      } else {
        assert pArchitecture.contains("64");
        arch = "64";
      }
    }

    @Override
    public boolean test(final Path pPath) {
      final int lastDirIndex = pPath.getNameCount() - 1;
      final String relevantDirName = pPath.getName(lastDirIndex).toString();
      return relevantDirName.contains(os) && relevantDirName.contains(arch);
    }
  }
}