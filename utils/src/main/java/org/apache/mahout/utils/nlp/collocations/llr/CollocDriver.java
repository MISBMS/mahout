/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.mahout.utils.nlp.collocations.llr;

import java.io.IOException;
import java.util.Map;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.DoubleWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapred.FileInputFormat;
import org.apache.hadoop.mapred.FileOutputFormat;
import org.apache.hadoop.mapred.JobClient;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.RunningJob;
import org.apache.hadoop.mapred.SequenceFileInputFormat;
import org.apache.hadoop.mapred.SequenceFileOutputFormat;
import org.apache.hadoop.mapred.lib.IdentityMapper;
import org.apache.hadoop.util.ToolRunner;
import org.apache.lucene.analysis.Analyzer;
import org.apache.mahout.common.AbstractJob;
import org.apache.mahout.common.HadoopUtil;
import org.apache.mahout.common.commandline.DefaultOptionCreator;
import org.apache.mahout.text.DefaultAnalyzer;
import org.apache.mahout.utils.vectors.text.DocumentProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Driver for LLR Collocation discovery mapreduce job */
public final class CollocDriver extends AbstractJob {
  public static final String DEFAULT_OUTPUT_DIRECTORY = "output";
  public static final String SUBGRAM_OUTPUT_DIRECTORY = "subgrams";
  public static final String NGRAM_OUTPUT_DIRECTORY = "ngrams";
  
  public static final String EMIT_UNIGRAMS = "emit-unigrams";
  public static final boolean DEFAULT_EMIT_UNIGRAMS = false;
  
  public static final int DEFAULT_MAX_NGRAM_SIZE = 2;
  public static final int DEFAULT_PASS1_NUM_REDUCE_TASKS = 1;
  
  private static final Logger log = LoggerFactory.getLogger(CollocDriver.class);

  private CollocDriver() {
  }

  public static void main(String[] args) throws Exception {
    ToolRunner.run(new CollocDriver(), args);
  }

  @Override
  public int run(String[] args) throws Exception {
    addInputOption();
    addOutputOption();
    addOption(DefaultOptionCreator.numReducersOption().create());
    
    addOption("maxNGramSize", "ng", 
        "(Optional) The max size of ngrams to create (2 = bigrams, 3 = trigrams, etc) default: 2",
        String.valueOf(DEFAULT_MAX_NGRAM_SIZE));
    addOption("minSupport", "s", 
        "(Optional) Minimum Support. Default Value: " + CollocReducer.DEFAULT_MIN_SUPPORT, 
        String.valueOf(CollocReducer.DEFAULT_MIN_SUPPORT));
    addOption("minLLR", "ml",
        "(Optional)The minimum Log Likelihood Ratio(Float)  Default is " + LLRReducer.DEFAULT_MIN_LLR,
        String.valueOf(LLRReducer.DEFAULT_MIN_LLR));
    addOption(DefaultOptionCreator.overwriteOption().create());
    addOption("analyzerName", "a",
        "The class name of the analyzer to use for preprocessing", null);
    
    addFlag("preprocess", "p",
        "If set, input is SequenceFile<Text,Text> where the value is the document, "
        + " which will be tokenized using the specified analyzer.");
    addFlag("unigram", "u", 
        "If set, unigrams will be emitted in the final output alongside collocations");
    
    Map<String, String> argMap = parseArguments(args);
    
    if (argMap == null) {
      return -1;
    }
    
    Path input = getInputPath();
    Path output = getOutputPath();
    
    
    int maxNGramSize = DEFAULT_MAX_NGRAM_SIZE;
    if (argMap.get("--maxNGramSize") != null) {
      try {
        maxNGramSize = Integer.parseInt(argMap.get("--maxNGramSize"));
      } catch (NumberFormatException ex) {
        log.warn("Could not parse ngram size option");
      }
    }
    log.info("Maximum n-gram size is: {}", maxNGramSize);
    
    
    if (argMap.containsKey("--overwrite")) {
      HadoopUtil.overwriteOutput(output);
    }
    
    
    int minSupport = CollocReducer.DEFAULT_MIN_SUPPORT;
    if (argMap.get("--minsupport") != null) {
      minSupport = Integer.parseInt(argMap.get("--minsupport"));
    }
    log.info("Minimum Support value: {}", minSupport);
    
    
    float minLLRValue = LLRReducer.DEFAULT_MIN_LLR;
    if (argMap.get("--minLLR") != null) {
      minLLRValue = Float.parseFloat(argMap.get("--minLLR"));
    }
    log.info("Minimum LLR value: {}", minLLRValue);
    
    
    int reduceTasks = DEFAULT_PASS1_NUM_REDUCE_TASKS;
    if (argMap.get("--maxRed") != null) {
      reduceTasks = Integer.parseInt(argMap.get("--maxRed"));
    }
    log.info("Number of pass1 reduce tasks: {}", reduceTasks);
    
    
    boolean emitUnigrams = argMap.containsKey("--emitUnigrams");

    if (argMap.containsKey("--preprocess")) {
      log.info("Input will be preprocessed");
      
      Class<? extends Analyzer> analyzerClass = DefaultAnalyzer.class;
      if (argMap.get("--analyzerName") != null) {
        String className = argMap.get("--analyzerName");
        analyzerClass = Class.forName(className).asSubclass(Analyzer.class);
        // try instantiating it, b/c there isn't any point in setting it if
        // you can't instantiate it
        analyzerClass.newInstance();
      }
      
      Path tokenizedPath = new Path(output, DocumentProcessor.TOKENIZED_DOCUMENT_OUTPUT_FOLDER);
      
      DocumentProcessor.tokenizeDocuments(input, analyzerClass, tokenizedPath);
      input = tokenizedPath;
    } else {
      log.info("Input will NOT be preprocessed");
    }
    
    // parse input and extract collocations
    long ngramCount = generateCollocations(input, output, getConf(), emitUnigrams, maxNGramSize,
      reduceTasks, minSupport);
    
    // tally collocations and perform LLR calculation
    computeNGramsPruneByLLR(output, getConf(), ngramCount, emitUnigrams, minLLRValue, reduceTasks);

    return 0;
  }
  
  /**
   * Generate all ngrams for the {@link org.apache.mahout.utils.vectors.text.DictionaryVectorizer} job
   * 
   * @param input
   *          input path containing tokenized documents
   * @param output
   *          output path where ngrams are generated including unigrams
   * @param maxNGramSize
   *          minValue = 2.
   * @param minSupport
   *          minimum support to prune ngrams including unigrams
   * @param minLLRValue
   *          minimum threshold to prune ngrams
   * @param reduceTasks
   *          number of reducers used
   * @throws IOException
   */
  public static void generateAllGrams(Path input,
                                      Path output,
                                      Configuration baseConf,
                                      int maxNGramSize,
                                      int minSupport,
                                      float minLLRValue,
                                      int reduceTasks) throws IOException {
    // parse input and extract collocations
    long ngramCount = generateCollocations(input, output, baseConf, true, maxNGramSize, reduceTasks,
      minSupport);
    
    // tally collocations and perform LLR calculation
    computeNGramsPruneByLLR(output, baseConf, ngramCount, true, minLLRValue, reduceTasks);
  }
  
  /**
   * pass1: generate collocations, ngrams
   */
  public static long generateCollocations(Path input,
                                          Path output,
                                          Configuration baseConf,
                                          boolean emitUnigrams,
                                          int maxNGramSize,
                                          int reduceTasks,
                                          int minSupport) throws IOException {
    
    JobConf conf = new JobConf(baseConf, CollocDriver.class);
    conf.setJobName(CollocDriver.class.getSimpleName() + ".generateCollocations:" + input);
    
    conf.setMapOutputKeyClass(GramKey.class);
    conf.setMapOutputValueClass(Gram.class);
    conf.setPartitionerClass(GramKeyPartitioner.class);
    conf.setOutputValueGroupingComparator(GramKeyGroupComparator.class);
    
    conf.setOutputKeyClass(Gram.class);
    conf.setOutputValueClass(Gram.class);
    
    conf.setCombinerClass(CollocCombiner.class);
    
    conf.setBoolean(EMIT_UNIGRAMS, emitUnigrams);
    
    FileInputFormat.setInputPaths(conf, input);
    
    Path outputPath = new Path(output, SUBGRAM_OUTPUT_DIRECTORY);
    FileOutputFormat.setOutputPath(conf, outputPath);
    
    conf.setInputFormat(SequenceFileInputFormat.class);
    conf.setMapperClass(CollocMapper.class);
    
    conf.setOutputFormat(SequenceFileOutputFormat.class);
    conf.setReducerClass(CollocReducer.class);
    conf.setInt(CollocMapper.MAX_SHINGLE_SIZE, maxNGramSize);
    conf.setInt(CollocReducer.MIN_SUPPORT, minSupport);
    conf.setNumReduceTasks(reduceTasks);
    
    RunningJob job = JobClient.runJob(conf);
    return job.getCounters().findCounter(CollocMapper.Count.NGRAM_TOTAL).getValue();
  }
  
  /**
   * pass2: perform the LLR calculation
   */
  public static void computeNGramsPruneByLLR(Path output,
                                                Configuration baseConf,
                                                long nGramTotal,
                                                boolean emitUnigrams,
                                                float minLLRValue,
                                                int reduceTasks) throws IOException {
    JobConf conf = new JobConf(baseConf, CollocDriver.class);
    conf.setJobName(CollocDriver.class.getSimpleName() + ".computeNGrams: " + output);
    
    
    conf.setLong(LLRReducer.NGRAM_TOTAL, nGramTotal);
    conf.setBoolean(EMIT_UNIGRAMS, emitUnigrams);
    
    conf.setMapOutputKeyClass(Gram.class);
    conf.setMapOutputValueClass(Gram.class);
    
    conf.setOutputKeyClass(Text.class);
    conf.setOutputValueClass(DoubleWritable.class);
    
    FileInputFormat.setInputPaths(conf, new Path(output, SUBGRAM_OUTPUT_DIRECTORY));
    Path outPath = new Path(output, NGRAM_OUTPUT_DIRECTORY);
    FileOutputFormat.setOutputPath(conf, outPath);
    
    conf.setMapperClass(IdentityMapper.class);
    conf.setInputFormat(SequenceFileInputFormat.class);
    conf.setOutputFormat(SequenceFileOutputFormat.class);
    conf.setReducerClass(LLRReducer.class);
    conf.setNumReduceTasks(reduceTasks);
    
    conf.setFloat(LLRReducer.MIN_LLR, minLLRValue);
    JobClient.runJob(conf);
  }
}