package opt.test;

import func.nn.backprop.BackPropagationNetwork;
import func.nn.backprop.BackPropagationNetworkFactory;
import func.nn.backprop.BatchBackPropagationTrainer;
import func.nn.backprop.RPROPUpdateRule;
import opt.OptimizationAlgorithm;
import opt.RandomizedHillClimbing;
import opt.SimulatedAnnealing;
import opt.example.NeuralNetworkOptimizationProblem;
import opt.ga.StandardGeneticAlgorithm;
import shared.*;
import util.linalg.Vector;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.text.DecimalFormat;
import java.util.*;

/**
 * Created by osama on 3/6/16.  
 * Additional edits made by Derrick Williams
 */
public class WhiteWineTest {

  private static String[] labels;
  private static Set<String> unique_labels;

  private static Instance[] allInstances;
  private static Map<String, double[]> bitvector_mappings;

//  These fields are hardcoded, depending on your problem

//  Filename for your csv dataset
  private static final String FILENAME = "src/opt/test/winequality-white.csv";

//  How many examples your have
  private static int num_examples = 4898;

//  How many fields are attributes. This is the number of columns you have minus 1.
//  The last column of your CSV will be used as the classification.
  private static int num_attributes = 11;

//  Number of input nodes is the same as the number of attributes for your problem
  private static int inputLayer = num_attributes;

//  TODO: Manipulate these value. They are your hyper parameters for the Neural Network
  private static int hiddenLayer = 20;
  private static int trainingIterations = 500;

//  This is determined later
  private static int outputLayer;


  private static BackPropagationNetworkFactory factory = new BackPropagationNetworkFactory();
  private static ErrorMeasure measure = new SumOfSquaresError();

  private static DecimalFormat df = new DecimalFormat("0.000");

//  Used by backprop
  private static double backprop_threshold = 1e-10;

//  Train and Test sets
  private static DataSet trainSet;
  private static DataSet testSet;

  private static final double PERCENT_TRAIN = 0.7;

  //  For cross fold validation
  private static final int K = 10;

  private static List<Instance[]> folds;


  //    TODO: Tweak these params for SA
  private static double temp = 1E11;
  private static double cooling = 0.999;

  //    TODO: Tweak these params for GA
  private static int populationSize = 200;
  private static int toMate = 100;
  private static int toMutate = 20;



  public static void main(String[] args) {
    initializeInstances();

//    Handles cross-fold validation using K folds
    makeTestTrainSets();
    folds = kfolds(trainSet);


    // Added CV runs to find best combination of hidden nodes and iterations in backprop
    Integer[] hiddenLayers    = new Integer[]{0,1,2,5,10,20};
    Integer[] iterations      = new Integer[]{1,5,10,50,100,250,500,1000,2000};
    Double[] temps            = new Double[]{1E2,1E5,1E8,1E11};
    Double[] coolings         = new Double[]{0.1,0.25,0.5,0.75,0.999};
    Integer[] populationSizes = new Integer[]{10,20,50,100,200};
    Integer[] toMates         = new Integer[]{1,5,10,20,50,100};
    Integer[] toMutates       = new Integer[]{1,5,10,20};

    //PrintWriter writer = new PrintWriter("results-20160310.txt", "UTF-8");
    //writer.println("The first line");
    //writer.println("The second line");


    // Backprop iterations
    for (int i = 0; i < hiddenLayers.length; i++){
      for (int j = 0; j < iterations.length; j++){
        hiddenLayer = hiddenLayers[i];
        trainingIterations = iterations[j];
        System.out.printf("%nhiddenlayer: %d %n", hiddenLayer);
        System.out.printf("iteration: %d %n", trainingIterations);
        runBackprop();
      }
    }
    //writer.close();

    // Set to optimum of 20 nodes and 1 hiddenlayer @ 1000 iterations
    hiddenLayer = 20;

    // RHC iterations
    for (int i = 0; i < iterations.length; i++){
        trainingIterations = iterations[i];
        System.out.printf("iteration: %d %n", trainingIterations);
        runRHC();
    }

    // SA iterations
    for (int i = 0; i < iterations.length; i++){
      for (int j = 0; j < temps.length; j++){
        for (int k = 0; k < coolings.length; k++){
          trainingIterations = iterations[i];
          temp    = temps[j]; 
          cooling = coolings[k];
          System.out.printf("iteration: %d %n", trainingIterations);
          System.out.printf("temp: %f %n", temp);
          System.out.printf("cooling: %f %n", cooling);
          runSA();
        }
      }
    }

    // GA iterations
    for (int i = 0; i < iterations.length; i++){
      for (int j = 0; j < populationSizes.length; j++){
        for (int k = 0; k < toMates.length; k++){
          for (int m = 0; m < toMutates.length; m++){
            if (toMutates[m] < toMates[k] && toMates[k] < populationSizes[j]){
              trainingIterations = iterations[i];
              populationSize     = populationSizes[j]; 
              toMate = toMates[k];
              toMutate = toMutates[m];
              System.out.printf("iteration: %d %n", trainingIterations);
              System.out.printf("populationSize: %d %n", populationSize);
              System.out.printf("toMate: %d %n", toMate);
              System.out.printf("toMutate: %d %n", toMutate);
              runGA();
            }
          }
        }
      }
    }

    //runBackprop();
    //runRHC();
    //runSA();
    //runGA();
  }

  /**
   * Randomized Hill Climbing with Random restarts
   */
  public static void runRHC() {

    System.out.println("===========Randomized Hill Climbing=========");

    BackPropagationNetwork[] nets = new BackPropagationNetwork[K];
    NeuralNetworkOptimizationProblem[] nnops = new NeuralNetworkOptimizationProblem[K];
    OptimizationAlgorithm[] oas = new OptimizationAlgorithm[K];





    double[] validationErrors = new double[nets.length];
    double[] trainErrors = new double[nets.length];

    //double starttime = System.nanoTime();;
    //double endtime;

    for (int i = 0; i < nets.length; i++) {

      Instance[] validation = getValidationFold(folds, i);
      Instance[] trainFolds = getTrainFolds(folds, i);
      DataSet trnfoldsSet = new DataSet(trainFolds);

      nets[i] = factory.createClassificationNetwork(
          new int[] {inputLayer, hiddenLayer, outputLayer});
      nnops[i] = new NeuralNetworkOptimizationProblem(trnfoldsSet, nets[i], measure);
      oas[i] = new RandomizedHillClimbing(nnops[i]);

      BackPropagationNetwork backpropNet = nets[i];

//      TODO: Vary the number of iterations as needed for your results
      train(oas[i], nets[i], trainingIterations);


      validationErrors[i] = evaluateNetwork(backpropNet, validation);
      System.out.printf("Fold: %d\t Accuracy: %f%%%n", i+1, (1-validationErrors[i]) * 100);
      //trainErrors[i] = evaluateNetwork(backpropNet, trainFolds);
    }

    double starttime = System.nanoTime();
    double endtime;
    // ----------------Train model----------------//
    BackPropagationNetwork backprop = factory.createClassificationNetwork(
        new int[] {inputLayer, hiddenLayer, outputLayer});
    NeuralNetworkOptimizationProblem nnopTrain = new NeuralNetworkOptimizationProblem(trainSet, backprop, measure);
    OptimizationAlgorithm oasTrain = new RandomizedHillClimbing(nnopTrain);

    //BackPropagationNetwork backpropNet = netTrain;
    train(oasTrain, backprop, trainingIterations);
    // ----------------Train model----------------//
    endtime = System.nanoTime();

    // Added average CV error
    double sum = 0;
    for (int j = 0; j < validationErrors.length; j++){
      sum += validationErrors[j];
    }
    double validationErrorAverage = sum / validationErrors.length;

    // int best_index = -1;
    // double min = Double.MAX_VALUE;
    // for (int j = 0; j < validationErrors.length; j++) {
    //   if (validationErrors[j] < min) {
    //     best_index = j;
    //     min = validationErrors[j];
    //   }
    // }



    //BackPropagationNetwork bestNet = nets[best_index];
    //double validationError = validationErrors[best_index];
    //double trainError = trainErrors[best_index];
    //double trainError = evaluateNetwork(bestNet, trainSet.getInstances());
    //double testError = evaluateNetwork(bestNet, testSet.getInstances());

    double trainErrorUpdated = evaluateNetwork(backprop, trainSet.getInstances());
    double testErrorUpdated = evaluateNetwork(backprop, testSet.getInstances());


    // System.out.printf("%nAverage Validation Accuracy: %f%% %n", (1-validationErrorAverage) * 100);
    // System.out.printf("%nMin Validation Error: %f%% %n", validationError * 100);
    // System.out.printf("Training Error: %f%% %n", trainError * 100);
    // System.out.printf("Test Error: %f%% %n", testError * 100);

    System.out.printf("%nAverage Validation Accuracy: %f%% %n", (1-validationErrorAverage) * 100);
    //System.out.printf("Max Validation Accuracy: %f%% %n", (1-validationError) * 100);
    //System.out.printf("Training Accuracy: %f%% %n", (1-trainError) * 100);
    //System.out.printf("Test Accuracy: %f%% %n", (1-testError) * 100);   
    System.out.printf("Training Updated Accuracy: %f%% %n", (1-trainErrorUpdated) * 100);
    System.out.printf("Test Updated Accuracy: %f%% %n", (1-testErrorUpdated) * 100);   


    //endtime = System.nanoTime();
    double time_elapsed = endtime - starttime;

//    Convert nanoseconds to seconds
    time_elapsed /= Math.pow(10,9);
    System.out.printf("Time Elapsed: %s s %n", df.format(time_elapsed));

  }

  /**
   * Run simulated annealing
   */
  public static void runSA() {

//    TODO: Tweak these params for SA
    //double temp = 1E11;
    //double cooling = 0.999;

    System.out.println("===========Simulated Annealing=========");

    BackPropagationNetwork[] nets = new BackPropagationNetwork[K];
    NeuralNetworkOptimizationProblem[] nnops = new NeuralNetworkOptimizationProblem[K];
    OptimizationAlgorithm[] oas = new OptimizationAlgorithm[K];

    double[] validationErrors = new double[nets.length];
    double[] trainErrors = new double[nets.length];

    //double starttime = System.nanoTime();;
    //double endtime;

    for (int i = 0; i < nets.length; i++) {

      Instance[] validation = getValidationFold(folds, i);
      Instance[] trainFolds = getTrainFolds(folds, i);
      DataSet trnfoldsSet = new DataSet(trainFolds);

      nets[i] = factory.createClassificationNetwork(
          new int[] {inputLayer, hiddenLayer, outputLayer});
      nnops[i] = new NeuralNetworkOptimizationProblem(trnfoldsSet, nets[i], measure);
      oas[i] = new SimulatedAnnealing(temp, cooling, nnops[i]);

      BackPropagationNetwork backpropNet = nets[i];

//      TODO: Vary the number of iterations as needed for your results
      train(oas[i], nets[i], trainingIterations);

      validationErrors[i] = evaluateNetwork(backpropNet, validation);
      System.out.printf("Fold: %d\t Accuracy: %f%%%n", i+1, (1-validationErrors[i]) * 100);
      trainErrors[i] = evaluateNetwork(backpropNet, trainFolds);
    }

    double starttime = System.nanoTime();
    double endtime;
    // ----------------Train model----------------//
    BackPropagationNetwork backprop = factory.createClassificationNetwork(
        new int[] {inputLayer, hiddenLayer, outputLayer});
    NeuralNetworkOptimizationProblem nnopTrain = new NeuralNetworkOptimizationProblem(trainSet, backprop, measure);
    OptimizationAlgorithm oasTrain = new SimulatedAnnealing(temp, cooling, nnopTrain);

    //BackPropagationNetwork backpropNet = netTrain;
    train(oasTrain, backprop, trainingIterations);
    // ----------------Train model----------------//
    endtime = System.nanoTime();

    // Added average CV error
    double sum = 0;
    for (int j = 0; j < validationErrors.length; j++){
      sum += validationErrors[j];
    }
    double validationErrorAverage = sum / validationErrors.length;

    // int best_index = -1;
    // double min = Double.MAX_VALUE;
    // for (int j = 0; j < validationErrors.length; j++) {
    //   if (validationErrors[j] < min) {
    //     best_index = j;
    //     min = validationErrors[j];
    //   }
    // }

    // BackPropagationNetwork bestNet = nets[best_index];
    // double validationError = validationErrors[best_index];
    // double trainError = trainErrors[best_index];
    // double testError = evaluateNetwork(bestNet, testSet.getInstances());
    double trainErrorUpdated = evaluateNetwork(backprop, trainSet.getInstances());
    double testErrorUpdated = evaluateNetwork(backprop, testSet.getInstances());

    // System.out.printf("%nAverage Validation Accuracy: %f%% %n", (1-validationErrorAverage) * 100);
    // System.out.printf("%nMin Validation Error: %f%% %n", validationError * 100);
    // System.out.printf("Training Error: %f%% %n", trainError * 100);
    // System.out.printf("Test Error: %f%% %n", testError * 100);

    System.out.printf("%nAverage Validation Accuracy: %f%% %n", (1-validationErrorAverage) * 100);
    //System.out.printf("Max Validation Accuracy: %f%% %n", (1-validationError) * 100);
    //System.out.printf("Training Accuracy: %f%% %n", (1-trainError) * 100);
    //System.out.printf("Test Accuracy: %f%% %n", (1-testError) * 100);   
    System.out.printf("Training Updated Accuracy: %f%% %n", (1-trainErrorUpdated) * 100);
    System.out.printf("Test Updated Accuracy: %f%% %n", (1-testErrorUpdated) * 100);   

    //endtime = System.nanoTime();
    double time_elapsed = endtime - starttime;

//    Convert nanoseconds to seconds
    time_elapsed /= Math.pow(10,9);
    System.out.printf("Time Elapsed: %s s %n", df.format(time_elapsed));
  }

  /**
   * Run genetic algorithms.
   */
  public static void runGA() {

//    TODO: Tweak these params for GA
    //int populationSize = 200;
    //int toMate = 100;
    //int toMutate = 20;

    System.out.println("===========Genetic Algorithms=========");

    BackPropagationNetwork[] nets = new BackPropagationNetwork[K];
    NeuralNetworkOptimizationProblem[] nnops = new NeuralNetworkOptimizationProblem[K];
    OptimizationAlgorithm[] oas = new OptimizationAlgorithm[K];

    double[] validationErrors = new double[nets.length];
    double[] trainErrors = new double[nets.length];

    //double starttime = System.nanoTime();;
    //double endtime;

    for (int i = 0; i < nets.length; i++) {

      Instance[] validation = getValidationFold(folds, i);
      Instance[] trainFolds = getTrainFolds(folds, i);
      DataSet trnfoldsSet = new DataSet(trainFolds);

      nets[i] = factory.createClassificationNetwork(
          new int[] {inputLayer, hiddenLayer, outputLayer});
      nnops[i] = new NeuralNetworkOptimizationProblem(trnfoldsSet, nets[i], measure);
      oas[i] = new StandardGeneticAlgorithm(populationSize, toMate, toMutate, nnops[i]);

      BackPropagationNetwork backpropNet = nets[i];

//      TODO: Vary the number of iterations as needed for your results
      train(oas[i], nets[i], trainingIterations);

      validationErrors[i] = evaluateNetwork(backpropNet, validation);
      System.out.printf("Fold: %d\t Accuracy: %f%%%n", i+1, (1-validationErrors[i]) * 100);
      trainErrors[i] = evaluateNetwork(backpropNet, trainFolds);
    }


    double starttime = System.nanoTime();
    double endtime;
    // ----------------Train model----------------//
    BackPropagationNetwork backprop = factory.createClassificationNetwork(
        new int[] {inputLayer, hiddenLayer, outputLayer});
    NeuralNetworkOptimizationProblem nnopTrain = new NeuralNetworkOptimizationProblem(trainSet, backprop, measure);
    OptimizationAlgorithm oasTrain = new StandardGeneticAlgorithm(populationSize, toMate, toMutate, nnopTrain);

    //BackPropagationNetwork backpropNet = netTrain;
    train(oasTrain, backprop, trainingIterations);
    // ----------------Train model----------------//
    endtime = System.nanoTime();


    // Added average CV error
    double sum = 0;
    for (int j = 0; j < validationErrors.length; j++){
      sum += validationErrors[j];
    }
    double validationErrorAverage = sum / validationErrors.length;


    // int best_index = -1;
    // double min = Double.MAX_VALUE;
    // for (int j = 0; j < validationErrors.length; j++) {
    //   if (validationErrors[j] < min) {
    //     best_index = j;
    //     min = validationErrors[j];
    //   }
    // }

    // BackPropagationNetwork bestNet = nets[best_index];
    // double validationError = validationErrors[best_index];
    // double trainError = trainErrors[best_index];
    // double testError = evaluateNetwork(bestNet, testSet.getInstances());

    double trainErrorUpdated = evaluateNetwork(backprop, trainSet.getInstances());
    double testErrorUpdated = evaluateNetwork(backprop, testSet.getInstances());

    // System.out.printf("%nAverage Validation Accuracy: %f%% %n", (1-validationErrorAverage) * 100);
    // System.out.printf("%nMin Validation Error: %f%% %n", validationError * 100);
    // System.out.printf("Training Error: %f%% %n", trainError * 100);
    // System.out.printf("Test Error: %f%% %n", testError * 100);

    System.out.printf("%nAverage Validation Accuracy: %f%% %n", (1-validationErrorAverage) * 100);
    //System.out.printf("Max Validation Accuracy: %f%% %n", (1-validationError) * 100);
    //System.out.printf("Training Accuracy: %f%% %n", (1-trainError) * 100);
    //System.out.printf("Test Accuracy: %f%% %n", (1-testError) * 100);   
    System.out.printf("Training Updated Accuracy: %f%% %n", (1-trainErrorUpdated) * 100);
    System.out.printf("Test Updated Accuracy: %f%% %n", (1-testErrorUpdated) * 100);   


    //endtime = System.nanoTime();
    double time_elapsed = endtime - starttime;

//    Convert nanoseconds to seconds
    time_elapsed /= Math.pow(10,9);
    System.out.printf("Time Elapsed: %s s %n", df.format(time_elapsed));
  }


  /**
   * This method will run Backpropagation using each
   * combination of (K-1) folds for training, and the Kth fold for validation. Once the model
   * with the lowest validation set error is found, that is used as the "best" model and the
   * training and test errors on that model are recorded.
   */
  public static void runBackprop() {

    System.out.println("===========Backprop=========");

    BackPropagationNetwork[] nets = new BackPropagationNetwork[K];
    double[] validationErrors = new double[nets.length];
    double[] trainErrors = new double[nets.length];

    //double starttime = System.nanoTime();;
    //double endtime;

    for (int i = 0; i < nets.length; i++) {

      Instance[] validation = getValidationFold(folds, i);
      Instance[] trainFolds = getTrainFolds(folds, i);
      DataSet trnfoldsSet = new DataSet(trainFolds);

      nets[i] = factory.createClassificationNetwork(
          new int[]{inputLayer, hiddenLayer, outputLayer});

      BackPropagationNetwork backpropNet = nets[i];

      ConvergenceTrainer trainer = new ConvergenceTrainer(
          new BatchBackPropagationTrainer(trnfoldsSet, backpropNet, new SumOfSquaresError(),
              new RPROPUpdateRule()),
          backprop_threshold, trainingIterations);

      trainer.train();

      validationErrors[i] = evaluateNetwork(backpropNet, validation);
      System.out.printf("Fold: %d\t Accuracy: %f%%%n", i+1, (1-validationErrors[i]) * 100);
      trainErrors[i] = evaluateNetwork(backpropNet, trainFolds);
    }

    // int best_index = -1;
    // double min = Double.MAX_VALUE;
    // for (int j = 0; j < validationErrors.length; j++) {
    //   if (validationErrors[j] < min) {
    //     best_index = j;
    //     min = validationErrors[j];
    //   }
    // }


    double starttime = System.nanoTime();
    double endtime;
    // ----------------Train model----------------//
    BackPropagationNetwork backprop = factory.createClassificationNetwork(
        new int[] {inputLayer, hiddenLayer, outputLayer});
    ConvergenceTrainer trainer = new ConvergenceTrainer(
          new BatchBackPropagationTrainer(trainSet, backprop, new SumOfSquaresError(),
              new RPROPUpdateRule()),
          backprop_threshold, trainingIterations);

    //BackPropagationNetwork backpropNet = netTrain;
    trainer.train();
    // ----------------Train model----------------//
    endtime = System.nanoTime();


    // Added average CV error
    double sum = 0;
    for (int j = 0; j < validationErrors.length; j++){
      sum += validationErrors[j];
    }
    double validationErrorAverage = sum / validationErrors.length;

    // BackPropagationNetwork bestNet = nets[best_index];
    // double validationError = validationErrors[best_index];
    // double trainError = trainErrors[best_index];
    // double testError = evaluateNetwork(bestNet, testSet.getInstances());
    double trainErrorUpdated = evaluateNetwork(backprop, trainSet.getInstances());
    double testErrorUpdated = evaluateNetwork(backprop, testSet.getInstances());

    System.out.println("\nConvergence in " + trainingIterations + " iterations");

    //System.out.printf("%nAverage Validation Error: %f%% %n", validationErrorAverage * 100);
    //System.out.printf("Min Validation Error: %f%% %n", validationError * 100);
    //System.out.printf("Training Error: %f%% %n", trainError * 100);
    //System.out.printf("Test Error: %f%% %n", testError * 100);

    System.out.printf("%nAverage Validation Accuracy: %f%% %n", (1-validationErrorAverage) * 100);
    //System.out.printf("Max Validation Accuracy: %f%% %n", (1-validationError) * 100);
    //System.out.printf("Training Accuracy: %f%% %n", (1-trainError) * 100);
    //System.out.printf("Test Accuracy: %f%% %n", (1-testError) * 100);    
    System.out.printf("Training Updated Accuracy: %f%% %n", (1-trainErrorUpdated) * 100);
    System.out.printf("Test Updated Accuracy: %f%% %n", (1-testErrorUpdated) * 100);   


    //endtime = System.nanoTime();
    double time_elapsed = endtime - starttime;

//    Convert nanoseconds to seconds
    time_elapsed /= Math.pow(10,9);
    System.out.printf("Time Elapsed: %s s %n", df.format(time_elapsed));

  }

  /**
   * Train a given optimization problem for a given number of iterations. Called by RHC, SA, and
   * GA algorithms
   * @param oa the optimization algorithm
   * @param network the network that corresponds to the randomized optimization problem. The
   *                optimization algorithm will determine the best weights to try using with this
   *                network and assign those weights
   * @param iterations the number of training iterations
   */
  private static void train(OptimizationAlgorithm oa, BackPropagationNetwork network, int
      iterations) {

    for(int i = 0; i < iterations; i++) {
      oa.train();
    }
    Instance optimalWeights = oa.getOptimal();
    network.setWeights(optimalWeights.getData());
  }

  /**
   * Given a network and instances, the output of the network is evaluated and a decimal value
   * for error is given
   * @param network the BackPropagationNetwork with weights already initialized
   * @param data the instances to be evaluated against
   * @return
   */
  public static double evaluateNetwork(BackPropagationNetwork network, Instance[] data) {

    double num_incorrect = 0;
    double error = 0;

    for (int j = 0; j < data.length; j++) {
      network.setInputValues(data[j].getData());
      network.run();

      Vector actual = data[j].getLabel().getData();
      Vector predicted = network.getOutputValues();


      boolean mismatch = ! isEqualOutputs(actual, predicted);

      if (mismatch) {
        num_incorrect += 1;
      }

    }

    error = num_incorrect / data.length;
    return error;

  }

  /**
   * Compares two bit vectors to see if expected bit vector is most likely to be the same
   * class as the actual bit vector
   * @param actual
   * @param predicted
   * @return
   */
  private static boolean isEqualOutputs(Vector actual, Vector predicted) {

    int max_at = 0;
    double max = 0;

//    Where the actual max should be
    int actual_index = 0;

    for (int i = 0; i < actual.size(); i++) {
      double aVal = actual.get(i);

      if (aVal == 1.0) {
        actual_index = i;
      }

      double bVal = predicted.get(i);

      if (bVal > max) {
        max = bVal;
        max_at = i;
      }
    }

    return actual_index == max_at;

  }

  /**
   * Reads a file formatted as CSV. Takes the labels and adds them to the set of labels (which
   * later helps determine the length of bit vectors). Records real-valued attributes. Turns the
   * attributes and labels into bit-vectors. Initializes a DataSet object with these instances.
   */
  private static void initializeInstances() {

    double[][] attributes = new double[num_examples][];

    labels = new String[num_examples];
    unique_labels = new HashSet<>();


//    Reading dataset
    try {
      BufferedReader br = new BufferedReader(new FileReader(new File(FILENAME)));

//      You don't need these headers, they're just the column labels

      String useless_headers = br.readLine();

      for(int i = 0; i < attributes.length; i++) {
        Scanner scan = new Scanner(br.readLine());
        scan.useDelimiter(";");

        attributes[i] = new double[num_attributes];

        for(int j = 0; j < num_attributes; j++) {
          attributes[i][j] = Double.parseDouble(scan.next());
        }

//        This last element is actually your classification, which is assumed to be a string
        labels[i] = scan.next();
        unique_labels.add(labels[i]);
      }
    }
    catch(Exception e) {
      e.printStackTrace();
    }


//    Creating a mapping of bitvectors. So "some classification" => [0, 1, 0, 0]
    int distinct_labels = unique_labels.size();
    outputLayer = distinct_labels;

    bitvector_mappings = new HashMap<>();

    int index = 0;
    for (String label : unique_labels) {
      double[] bitvect = new double[distinct_labels];

//      At index, set to 1 for a given string
      bitvect[index] = 1.0;
//      Increment which index will have a bit flipped in next classification
      index++;

      bitvector_mappings.put(label, bitvect);
    }

//    Replaces the label for each instance with the corresponding bit vector for that label
//    This works even for binary classification
    allInstances = new Instance[num_examples];
    for (int i = 0; i < attributes.length; i++) {
      double[] X = attributes[i];

      String label = labels[i];
      double[] bitvect = bitvector_mappings.get(label);

      Instance instance = new Instance(X);
      instance.setLabel(new Instance(bitvect));

      allInstances[i] = instance;
    }
  }

  /**
   * Print out the actual vs expected bit-vector. Used for debugging purposes only
   * @param actual what the example's actual bit vector looks like
   * @param expected what a network output as a bit vector
   */
  public static void printVectors(Vector actual, Vector expected) {
    System.out.print("Actual: [");
    for (int i = 0; i < actual.size(); i++) {
      System.out.printf(" %f", actual.get(i));
    }
    System.out.print(" ] \t Expected: [");

    for (int i = 0; i < expected.size(); i++) {
      System.out.printf(" %f", expected.get(i));
    }
    System.out.println(" ]");
  }

  /**
   * Takes all instances, and randomly orders them. Then, the first PERCENT_TRAIN percentage of
   * instances form the trainSet DataSet, and the remaining (1 - PERCENT_TRAIN) percentage of
   * instances form the testSet DataSet.
   */
  public static void makeTestTrainSets() {

    List<Instance> instances = new ArrayList<>();

    for (Instance instance: allInstances) {
      instances.add(instance);
    }
    // Shuffle instances
    Collections.shuffle(instances); 

    int cutoff = (int) (instances.size() * PERCENT_TRAIN);

    List<Instance> trainInstances = instances.subList(0, cutoff);
    List<Instance> testInstances = instances.subList(cutoff, instances.size());

    Instance[] arr_trn = new Instance[trainInstances.size()];
    trainSet = new DataSet(trainInstances.toArray(arr_trn));

    Instance[] arr_tst = new Instance[testInstances.size()];
    testSet = new DataSet(testInstances.toArray(arr_tst));

  }

  /**
   * Given a DataSet of training data, separate the instances into K nearly-equal-sized
   * partitions called folds for K-folds cross validation
   * @param training, the training DataSet
   * @return a list of folds, where each fold is an Instance[]
   */
  public static List<Instance[]> kfolds(DataSet training) {

    Instance[] trainInstances = training.getInstances();

    List<Instance> instances = new ArrayList<>();
    for (Instance instance: trainInstances) {
      instances.add(instance);
    }

    List<Instance[]> folds = new ArrayList<>();

//    Number of values per fold
    int per_fold = (int) Math.floor((double)(instances.size()) / K);

    int start = 0;
    int end = per_fold;

    while (start < instances.size()) {


      List<Instance> foldList = null;

      if (end > instances.size()) {
        end = instances.size();
      }
      foldList = instances.subList(start, end);

      Instance[] fold = new Instance[foldList.size()];
      fold = foldList.toArray(fold);

      folds.add(fold);

      start = end + 1;
      end = start + per_fold;

    }
    return folds;
  }

  /**
   * Given a list of Instance[], this helper combines each arrays contents into one, single
   * output array
   * @param instanceList the list of Instance[]
   * @return the combined array consisting of the contents of each Instance[] in instanceList
   */
  public static Instance[] combineInstances(List<Instance[]> instanceList) {
    List<Instance> combined = new ArrayList<>();

    for (Instance[] fold: instanceList) {

      for (Instance instance : fold) {
        combined.add(instance);
      }
    }

    Instance[] combinedArr = new Instance[combined.size()];
    combinedArr = combined.toArray(combinedArr);
    return combinedArr;
  }

  /**
   * Given a list of folds and an index, it will provide an Instance[] with the combined
   * instances from every fold except for the fold at the given index
   * @param folds the K-folds, a list of Instance[] used as folds for cross-validation
   * @param foldIndex the index of the fold to exclude. That fold is used as the validation set
   * @return the training folds combined into once Instance[]
   */
  public static Instance[] getTrainFolds(List<Instance[]> folds, int foldIndex) {
    List<Instance[]> trainFolds = new ArrayList<>(folds);
    trainFolds.remove(foldIndex);

    Instance[] trnfolds = combineInstances(trainFolds);
    return trnfolds;
  }

  /**
   * Given a list of folds and an index, it will provide an Instance[] to serve as a validation
   * set.
   * @param folds the K-folds, a list of Instance[] used as folds for cross-validation
   * @param foldIndex the index of the fold to use as the validation set
   * @return the validation set
   */
  public static Instance[] getValidationFold(List<Instance[]> folds, int foldIndex) {
    return folds.get(foldIndex);
  }

}
