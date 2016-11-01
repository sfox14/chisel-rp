#include <stdlib.h>
#include <stdio.h>
#include <math.h>
#include <time.h>

#define SEC_TO_NS (1000000000)

float * computeKernelVec(int features, int dictSize, float gamma, float * x, float ** dict, float * result) {
  int i, j;
  for ( i = 0; i < dictSize; i++) {
    float sum = 0;
    for ( j = 0; j < features; j++) {

      float tmp = x[j] - dict[i][j];
      sum += tmp*tmp;
    }
    sum *= -gamma;
    result[i] = expf(sum);
    //printf("%f %f  ", sum, dict[i][1]);
  }
  //printf("\n");
  for (i=0; i<dictSize; i++){
    //printf("%.8f ", result[i]);
  }
  //printf("   ");
  
  return result;
}

float getFt(int features, int dictSize, float gamma, float * x,
	    float ** dict, float * weights, float * tmp) {
  int i;
  //printf("%f \n", x[1]);
  computeKernelVec(features, dictSize, gamma, x, dict, tmp);
  float sum = 0;
  for ( i = 0; i < dictSize; i++)
    sum += weights[i]*tmp[i];
  //printf("%.3f \n", sum);
  return sum;
}

float * forgetWeights(int dictSize, float forget, float * weights){
  int i;
  for ( i = 0; i < dictSize; i++ )
    weights[i] *= forget;
  return weights;
}

int addToDict(int dictSize, int features, float * weights,
	      float ** dict, float * x, float newWeight, int idx){
  weights[idx] = newWeight;
  int i;
  //printf("added : %f \n", x[1]);
  for (i = 0; i < features; i++)
    dict[idx][i] = x[i];
  return (idx + 1) % dictSize;
}

void randProj(int features, int components, float *x, int **rm, float *res ){

  int i,j;
  for (i=0; i<components; i++){
    float sum = 0;
    for (j=0; j<features; j++){
      float pp = x[j]*rm[i][j];
      sum += pp;
    }
    res[i] = sum;
  }

}

void randProj3025(int features, int components, float *x, int **rm, float *res ){

  res[0] = x[15];
  res[1] = x[7];
  res[2] = x[28] - x[22];
  res[3] = - x[14];
  res[4] = x[11] -x[5];
  res[5] = x[7];
  res[6] = x[8] -x[9];
  res[7] = -x[14];
  res[8] = x[19];
  res[9] = x[8] + x[13];
  res[10] = x[28];
  res[11] = -x[4];
  res[12] = x[0] ;
  res[13] = x[17];
  res[14] = x[18] - x[22];
  res[15] = -x[4];
  res[16] = -x[25];
  res[17] = x[11] + x[9];
  res[18] = x[1] ;
  res[19] = x[21] -x[12];
  res[20] = -x[25];
  res[21] = x[9] + x[9];
  res[22] = x[1] ;
  res[23] = x[28];
  res[24] = x[1];

}

void randProj3020(int features, int components, float *x, int **rm, float *res ){

  res[0] = x[1] - x[15];
  res[1] = x[7];
  res[2] = x[28] - x[22];
  res[3] = - x[14] - x[10];
  res[4] = x[11] -x[5];
  res[5] = x[7];
  res[6] = x[8] -x[9];
  res[7] = -x[14] -x[10];
  res[8] = x[19];
  res[9] = x[7] + x[13];
  res[10] = x[28] -x[2];
  res[11] = -x[4] -x[29];
  res[12] = x[5] ;
  res[13] = x[17];
  res[14] = x[18] - x[22];
  res[15] = -x[4] -x[0];
  res[16] = -x[25];
  res[17] = x[11] + x[9];
  res[18] = x[1] ;
  res[19] = x[21] -x[12];

}

void randProj3015(int features, int components, float *x, int **rm, float *res ){

  res[0] = x[1] - x[15];
  res[1] = x[7] + x[26];
  res[2] = x[28] - x[22];
  res[3] = - x[14] - x[10];
  res[4] = x[11] -x[5];
  res[5] = x[7] +x[21];
  res[6] = x[8] -x[9];
  res[7] = -x[14] -x[10];
  res[8] = x[19] - x[15];
  res[9] = x[7] + x[13];
  res[10] = x[28] -x[2];
  res[11] = -x[4] -x[0];
  res[12] = x[11] - x[5];
  res[13] = x[17] + x[6];
  res[14] = x[21] - x[22];

}

void randProj3010(int features, int components, float *x, int **rm, float *res ){

  res[0] = x[1] - x[15] + x[3];
  res[1] = - x[22] - x[9] - x[2];
  res[2] = x[28] - x[22] + x[12] - x[3];
  res[3] = - x[14] - x[10] + x[8];
  res[4] = x[23] - x[22];
  res[5] = x[1] - x[7] - x[18] ;
  res[6] = x[7] + x[26] - x[2];
  res[7] = x[28] - x[22] + x[2];
  res[8] = - x[14] - x[10] + x[8];
  res[9] = x[11] -x[5] - x[4];

}

void randProj305(int features, int components, float *x, int **rm, float *res ){

  res[0] = x[1] - x[15] + x[3] - x[7] - x[18] + x[19];
  res[1] = x[7] + x[26] - x[22] - x[9] + x[27] - x[2];
  res[2] = x[28] - x[22] + x[2] + x[12] - x[3];
  res[3] = - x[14] - x[10] + x[6] + x[21] + x[8];
  res[4] = x[11] -x[5] - x[4] + x[23] - x[22];

}

void randProj84(int features, int components, float *x, int **rm, float *res ){

  res[0] = x[1] - x[0];
  res[1] = x[7] + x[2];
  res[2] = x[6] - x[3];
  res[3] = - x[6] - x[5];

}


int main(int argv, char * args []) {
  char *input = args[1];
  float gamma = 0.0506246281646581;
  float forget = 0.95;
  int dictSize; //200;
  int features;
  int components;
  float nu = 0.5;
  float eta = 1 - forget;
  float rho_add = -eta*(1 - nu);
  float rho_notadd = eta*nu;
  
  int mode;
  mode = atol(input);

  if (mode == 1){
      printf("running random projections + norma ... \n");
      dictSize = 25;
      features = 8;
      components = 4;
      printf("dictSize = %d \nfeatures = %d \ncomponents = %d \n\n", dictSize, features, components);
  }
  else if (mode == 2){
      printf("running norma alone ... \n");
      dictSize = 14;
      features = 8;
      components = 4;
      printf("dictSize = %d \nfeatures = %d \ncomponents = %d \n\n", dictSize, features, components);
  }
  else{
      printf("running random projection alone ... \n");
      dictSize = 10;
      features = 8;
      components = 4;
      printf("dictSize = %d \nfeatures = %d \ncomponents = %d \n\n", dictSize, features, components);
  }


  float ** dict = (float**)malloc(sizeof(float*)*dictSize);
  int i, j;
  for ( i = 0; i < dictSize; i++ )
    dict[i] = (float*)malloc(sizeof(float)*features);
  float * tmp = (float*)malloc(sizeof(float)*dictSize);
  float * weights = (float*)malloc(sizeof(float)*dictSize);
  for ( i = 0; i < dictSize; i++ )
    weights[i] = 0;
  //float * x = (float*)malloc(sizeof(float)*features);

  // random matrix
  int **rmatrix = (int**)malloc(sizeof(int*)*features);
  for ( i = 0; i < features; i++){
    rmatrix[i] = (int*)malloc(sizeof(int)*components);
  }

  time_t t;
  srand((unsigned) time(&t));

  for (i = 0; i<features; i++ ){
    for (j=0; j<components; j++) {
      rmatrix[i][j] = (rand()%2)*2 -1;
      printf("%d ", rmatrix[i][j]);
    }
    printf("\n");
  }

  //printf("%f \n", x[1]);

  int noEx = 100000;

  float ** examples = (float**)malloc(sizeof(float*)*noEx);
  for ( i = 0; i < noEx; i++) {
    examples[i] = (float*)malloc(sizeof(float)*features);
    for ( j = 0; j < features; j++)
      examples[i][j] = (float)rand()/(float)(RAND_MAX);
  }

  float *xex = (float*)malloc(sizeof(float)*components);

  // internal state
  int idx = 0;
  float rho = 0;
  float ft = 0;
  float ex = 0;
  
  struct timespec * start = (struct timespec *)malloc(sizeof(struct timespec));
  struct timespec * stop = (struct timespec *)malloc(sizeof(struct timespec));

    
  if (mode == 1){
    // Start the timer
    clock_gettime(CLOCK_REALTIME, start);

    for ( i = 0; i < noEx; i++ ) {
      randProj84(features, components, examples[i], rmatrix, xex);
      
      ft = getFt(features, dictSize, gamma, xex, dict, weights, tmp);

      // compute novelty update as its the fastest
      forgetWeights(dictSize, forget, weights);
      if (ft < rho) {
        rho = rho + rho_add;
        idx = addToDict(dictSize, features, weights, dict, examples[i], eta, idx);
        //printf("%d\n", idx);
      } else
        rho = rho + rho_notadd;
    }
    // Stop the timer
    clock_gettime(CLOCK_REALTIME, stop);
  }

  else if (mode == 2){
    clock_gettime(CLOCK_REALTIME, start);
    for ( i = 0; i < noEx; i++ ) {
      ft = getFt(features, dictSize, gamma, examples[i], dict, weights, tmp);
      
      // compute novelty update as its the fastest
      forgetWeights(dictSize, forget, weights);
      if (ft < rho) {
        rho = rho + rho_add;
        idx = addToDict(dictSize, features, weights, dict, examples[i], eta, idx);
        //printf("%d\n", idx);
      } else
        rho = rho + rho_notadd;
    }
    clock_gettime(CLOCK_REALTIME, stop);
  }

  else{
    // Start the timer
    clock_gettime(CLOCK_REALTIME, start);

    for ( i = 0; i < noEx; i++ ) {
      randProj84(features, components, examples[i], rmatrix, xex);
    }
    clock_gettime(CLOCK_REALTIME, stop);

  }
  int p;
  for(p; p<components; p++){
    printf("%f ", xex[p]);
  }
  printf("\n");
    

  // Stop the timer
  clock_gettime(CLOCK_REALTIME, stop);
  
  // print at end again so not optimized out
  printf("ft = %f\n", ft);
  long int totalTime = (stop->tv_sec*SEC_TO_NS + stop->tv_nsec) - (start->tv_sec*SEC_TO_NS + start->tv_nsec);
  printf("time = %lu ns\n", totalTime);
  printf("freq = %f Mhz\n", noEx*1000/((float)totalTime));
  printf("latency = %f ns\n", ((float)totalTime)/noEx);
  

  // Free memory
  for ( i = 0; i < noEx; i++)
    free(examples[i]);
  free(examples);
  //free(x);
  free(weights);
  free(tmp);
  for (i = 0; i < dictSize; i++)
    free(dict[i]);
  free(dict);
  return 0;
}
