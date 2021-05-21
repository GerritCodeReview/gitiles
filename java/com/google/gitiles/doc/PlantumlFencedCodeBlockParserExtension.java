// Copyright (C) 2021 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gitiles.doc;

import org.commonmark.Extension;
import org.commonmark.internal.BlockStartImpl;
import org.commonmark.internal.FencedCodeBlockParser;
import org.commonmark.node.Block;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.parser.Parser;
import org.commonmark.parser.Parser.ParserExtension;
import org.commonmark.parser.block.BlockContinue;
import org.commonmark.parser.block.BlockParser;
import org.commonmark.parser.block.BlockStart;
import org.commonmark.parser.block.MatchedBlockParser;
import org.commonmark.parser.block.ParserState;


/** CommonMark extension for {@code ```plantuml}. */
public class PlantumlFencedCodeBlockParserExtension implements ParserExtension {
  public static Extension create() {
    return new PlantumlFencedCodeBlockParserExtension();
  }

  private PlantumlFencedCodeBlockParserExtension() {}

  @Override
  public void extend(Parser.Builder builder) {
    builder.customBlockParserFactory(new PlantumlFencedCodeBlockParserFactory());
  }

  private static class PlantumlFencedCodeBlockParser extends FencedCodeBlockParser {
    public PlantumlFencedCodeBlockParser(char fenceChar, int fenceLength, int fenceIndent) {
      super(fenceChar, fenceLength, fenceIndent);
      // TODO Auto-generated constructor stub
    }

//    private final PlantumlFencedCodeBlock block = new PlantumlFencedCodeBlock();

    @Override
    public Block getBlock() {
      return super.getBlock();
    }

    @Override
    public BlockContinue tryContinue(ParserState parserState) {
      return super.tryContinue(parserState);
    }

    @Override
    public void addLine(CharSequence line) {
      super.addLine(line);
    }

    @Override
    public void closeBlock() {
      super.closeBlock();
    }
  }

  private static class PlantumlFencedCodeBlockParserFactory extends FencedCodeBlockParser.Factory {
    @Override
    public BlockStart tryStart(ParserState state, MatchedBlockParser matched) {
      BlockStart parentBlockStart = super.tryStart(state, matched);
      if (parentBlockStart != null) {
        if (parentBlockStart instanceof BlockStartImpl) {
          int newIndex = 0;
          BlockStartImpl parentBlockStartImpl = (BlockStartImpl) parentBlockStart;
          if (parentBlockStartImpl.getNewIndex() != -1) {
            newIndex = parentBlockStartImpl.getNewIndex();
          }
          if (state.getIndent() == 0) {
            CharSequence line = state.getLine();
            CharSequence nextSequence = line.subSequence(newIndex, line.length());
            if ("plantuml".contentEquals(nextSequence)) {
              BlockParser[] blockParsers = parentBlockStartImpl.getBlockParsers();
              Block block = blockParsers[0].getBlock();
              if (block instanceof FencedCodeBlock) {
                FencedCodeBlock fencedBlock = (FencedCodeBlock) block;
                return BlockStart.of(new PlantumlFencedCodeBlockParser(fencedBlock.getFenceChar(), fencedBlock.getFenceIndent(), fencedBlock.getFenceLength() + line.length())).atIndex(line.length());
              }
              return parentBlockStart;
            }
          }
        }
      }
      return BlockStart.none();
    }
  }
}
