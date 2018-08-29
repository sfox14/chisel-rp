import time
import pandas as pd
import numpy as np
import os
import os.path
import sys
import optunity
import optunity.metrics
import time
import json
import csv

mem_csv = "../../resources/mem.csv"
seeds_csv = "../../resources/seeds.csv"
params = {"gamma": 0.0506246282, "forget": 0.95, "eta": 0.05, "nu": 0.1}

notes_str = """** The path/location of the random seed and non-zero csv files
are hard coded above. The gamma, eta, forget and nu parameters are also
hard coded. Amend this script manually to test different parameters"""

class TypeError(Exception):
    pass

class TaskError(TypeError):
    def __init__(self, task, target):
        self.message = 'Task does not match Target: %d != %d' %(task,target)
        super(TaskError, self).__init__(self.message)

class RandProjError(TypeError):
    def __init__(self, j, n_feats):
        self.message = 'Matrix rij does not match n_feats: %d != %d' %(j,n_feats)
        super(RandProjError, self).__init__(self.message)


def saveOutputFile(outname, optimal_pars, opt):
    out = optimal_pars
    out['n_evals']=n_evals
    out['opt']=opt
    if os.path.isfile(outname):
        ans = raw_input("Overwrite (Y/N): ")
        if ans == 'Y' or ans == 'y':
            json.dump(out, open(outname, 'w'))
            print "saved params => %s" %outname
        else:
            print "params not saved"
    else:
        json.dump(out, open(outname, 'w'))
        print "saved params => %s" %outname


def printStatus(i, total, point, increment):
    #stuff for printing on screen
    total = 1000
    point = total / 100
    increment = total / 20
    for i in xrange(total):
        if(i % (5 * point) == 0):
            sys.stdout.write("\r[" + "=" * (i / increment) +  " " * ((total - i)/ increment) + "]" +  str(i / point) + "%")
            sys.stdout.flush()


def dataset_split(dataset, split=0.8):

    filename = dataset

    data = pd.DataFrame.from_csv(filename,index_col=False,header=None)
    n_feats = data.shape[1]-3
    Y = data[2].values
    X = data.loc[:, 3:].values

    if split < 1.:
        length = int(X.shape[0]*split)
        x_train = X[:length]
        y_train = Y[:length]
        x_test = X[length:]
        y_test = Y[length:]
        return x_train, y_train, x_test, y_test
    else:
        return X,Y,None,None


class LFSR(object):

    def __init__(self, seed, taps=[0, 2, 3, 5]):
        self.seed = seed
        self.taps = taps
        self.state = '{0:016b}'.format(self.seed) #bitstring

    def next(self):
        xor = 0
        for t in self.taps:
            xor += int(self.state[15-t])
        if xor%2 == 0.0:
            xor = 0
        else:
            xor = 1

        self.state = str(xor) + self.state[:-1]
        return xor

    def reset(self):
        self.state = '{0:016b}'.format(self.seed)


def generateRandomMatrix(mem_file, seeds_file):
    # load random matrix from data/mem.csv and seeds.csv
    x = np.genfromtxt(mem_file, delimiter=",", dtype=None).astype(int)
    seeds = pd.DataFrame.from_csv(seeds_file, index_col=False, header=None).values.astype(int).reshape(-1,)
    
    #initialise random matrix
    rij = np.zeros(x.shape)

    #make sure that mem matches seeds
    if x.shape[1] != seeds.shape[0]:
        print "Error: mem.csv does not match seeds.csv"

    #initialise lfsrs - pseudo random number generators
    lfsrs = [LFSR(seeds[i]) for i in range(seeds.shape[0])]

    #create random matrix
    for j in range(x.shape[1]):
        lfsr = lfsrs[j] #each dimension j represents a unique n_component and LFSR
        for i in range(x.shape[0]):
            rnd = lfsr.next()
            rnd = rnd*2 -1
            rij[i][j] = x[i][j]*rnd
        lfsr.reset() #no real need for this

    return rij,x.shape


def computeKernel(X, dictionary, gamma):

    dictSize = dictionary.shape[0]

    result = np.zeros(dictSize)
    for j in range(dictSize):
        tot = 0
        for i, x in enumerate(X):
            tmp = x - dictionary[j][i]
            #if tmp*tmp > 16:
            #    print tmp,
            tot += tmp*tmp
        tot = tot*gamma*-1.
        result[j] = np.exp(tot)

    return result


class NORMA (object):

    def __init__(self, p, appType, rij=None):
        self.gamma = p['gamma']
        self.forget = p['forget']
        self.dictSize = p['dictSize']
        self.nu = p['nu']
        self.n_feats = p['n_feats'] if rij is None else rij.shape[1]
        self.eta = p['eta'] #1-self.forget
        self.rho_add = -self.eta*(1-self.nu)
        self.rho_notadd = self.eta*self.nu
        self.rho = 0
        self.eps = 0
        self.b = 0
        self.appType = appType

        self.dict = np.zeros((self.dictSize, self.n_feats))
        self.weights = np.ones(self.dictSize)*1.2
        self.state = 0
        self.rij = rij # random matrix
        if self.rij is not None and self.rij.shape[1] != self.n_feats:
            raise RandProjError(self.rij.shape[1], self.n_feats)


    def fwd(self, X):
        # a single fwd run on novelty detection
        k = computeKernel(X, self.dict, self.gamma)
        fx = np.sum(self.weights*k)
        return fx

    def nov(self, X):
        # one example, calculate fx and update params
        fx = self.fwd(X)

        #updates
        self.weights = self.weights*self.forget
        
        if fx < self.rho:
            self.rho = self.rho + self.rho_add
            self.dict[self.state] = X
            self.weights[self.state] = self.eta
            self.state = (self.state + 1)%self.dictSize
        else:
            self.rho = self.rho + self.rho_notadd

        return fx

    def reg(self, X, Y):
        # one example, calculate fx and update params
        
        fx = self.fwd(X)
        error = Y-fx
        sig = 1
        if error <0:
            sig = -1
        
        #updates
        self.weights = self.weights*self.forget

        if abs(error) > self.eps:
            self.eps = self.eps - self.rho_add
            self.dict[self.state] = X
            self.weights[self.state] = self.eta*sig
            self.state = (self.state + 1)%self.dictSize
        else:
            self.eps = self.eps - self.rho_notadd
        #print self.state, abs(error)

        return fx

    def clas(self, X, Y):
        # one example, calculate fx and update params
        fx = self.fwd(X)

        #updates
        self.weights = self.weights*self.forget

        comp = Y*(fx+self.b)
        
        if comp < self.rho:
            self.rho = self.rho + self.rho_add
            self.b = self.b + (self.eta*Y)
            self.dict[self.state] = X
            self.weights[self.state] = self.eta*Y
            self.state = (self.state + 1)%self.dictSize
        else:
            self.rho = self.rho + self.rho_notadd

        return fx

    def fit(self, x_train, y_train):

        if self.appType == 1:
        # Classification
            for i in range(x_train.shape[0]):
                x = x_train[i]
                if self.rij is not None:
                    # do random projection
                    x = np.dot(x.reshape(1,-1), self.rij).reshape(-1,)
                fx = self.clas(x, y_train[i])
                #print np.max(x), np.min(x), fx
                #time.sleep(0.5)

        elif self.appType == 2:
        # Novelty Detection
            for i in range(x_train.shape[0]):
                x = x_train[i]
                if self.rij is not None:
                    # do random projection
                    x = np.dot(x.reshape(1,-1), self.rij).reshape(-1,)
                fx = self.nov(x)

        elif self.appType == 3:  
        # Regression  
            for i in range(x_train.shape[0]):
                x = x_train[i]
                if self.rij is not None:
                    # do random projection
                    x = np.dot(x.reshape(1,-1), self.rij).reshape(-1,)
                fx = self.reg(x, y_train[i])

    def predict(self, x_test, y_test):

        predictions = np.zeros(y_test.shape)

        if self.appType == 1:
            for i in range(x_test.shape[0]):
                xt = x_test[i]
                if self.rij is not None:
                    # do random projection
                    xt = np.dot(xt.reshape(1,-1), self.rij).reshape(-1,)
                fx = self.clas(xt, y_test[i])
                predictions[i] = 1 if fx > 0 else -1

        elif self.appType == 2:
            for i in range(x_test.shape[0]):
                xt = x_test[i]
                if self.rij is not None:
                    # do random projection
                    xt = np.dot(xt.reshape(1,-1), self.rij).reshape(-1,)
                fx = self.nov(xt)
                predictions[i] = 1 if fx < self.rho else -1


        elif self.appType == 3:
            for i in range(x_test.shape[0]):
                xt = x_test[i]
                #print xt
                if self.rij is not None:
                    # do random projection
                    xt = np.dot(xt.reshape(1,-1), self.rij).reshape(-1,)
                #print xt
                fx = self.reg(xt, y_test[i])
                predictions[i] = fx
                #print fx, y_test[i]
        return predictions


def regression(x_train, y_train, x_test, y_test, gamma, forget, eta, nu, dictSize, RP=None):

    params = {'gamma': gamma, 'forget': forget, 'eta': eta, 'dictSize': dictSize, 'nu': nu, 'n_feats': x_train.shape[1]}
    model = NORMA(params, 3, rij=RP)
    model.fit(x_train, y_train)
    predictions = model.predict(x_test, y_test)

    mse = optunity.metrics.mse(y_test, predictions)
    print "MSE: ", mse, " Gamma: ", gamma, " Forget: ", forget, " Eta: ", eta, " Nu: ", nu
    return mse


def classification(x_train, y_train, x_test, y_test, gamma, forget, eta, nu, dictSize, RP=None):

    params = {'gamma': gamma, 'forget': forget, 'eta': eta, 'dictSize': dictSize, 'nu': nu, 'n_feats': x_train.shape[1]}
    model = NORMA(params, 1, rij=RP)
    model.fit(x_train, y_train)
    predictions = model.predict(x_test, y_test)

    auc = optunity.metrics.roc_auc(y_test, predictions)
    print "AUC: ", auc, " Gamma: ", gamma, " Forget: ", forget, " Eta: ", eta, " Nu: ", nu
    return auc


def novelty_detection(x_train, y_train, x_test, y_test, gamma, forget, eta, nu, dictSize, RP=None):

	params = {'gamma': gamma, 'forget': forget, 'eta': eta, 'dictSize': dictSize, 'nu': nu, 'n_feats': x_train.shape[1]}
	model = NORMA(params, 2, rij=RP)
	model.fit(x_train, y_train)
	predictions = model.predict(x_test, y_test)

	return optunity.metrics.roc_auc(y_test, predictions)


def optimise_reg(d, solver='particle swarm', n_evals = 3, task=3, ts=1, RP=False):
    # other solvers may be better {particle swarm, nelder-mead, sobol, random search, grid search}
    #print optunity.available_solvers() #for more info.
    n_folds = 5
    rij = None #default value for random matrix

    print "Solver:      ", solver
    print "Dataset:     ", d
    print "Task:        ", task
    print "TimeSeries:  ", ts!=0
    print "RandProj:    ", RP


    if task != 3:
        raise TaskError(task, 3)

    if RP:
        rij,trans = generateRandomMatrix(mem_csv, seeds_csv)
        print "Proj:        ", trans

    print "n_evals:     ", n_evals 
    print "\n     Solving     ..."

    def time_series():
        # the order of examples is dependent on time, and must be preserved
        # i.e. we can not randomly shuffle the data
        x_train, y_train, x_test, y_test = dataset_split(d, split=0.8)
        dictSize = 200

        def f(gamma, forget, eta, nu):
            gamma = np.exp(gamma)
            return regression(x_train, y_train, x_test, y_test, gamma, forget, eta, nu, dictSize, RP=rij)

        return optunity.minimize(f, num_evals=n_evals, gamma=[-10,0], \
                                    forget=[0.98,1], eta=[0,0.5], nu=[0, 1], solver_name=solver )
        


    def normal():
        # we use a different optimizer. one which we can do cross-validation.
        X, Y, _, _ = dataset_split(d, split=1)
        dictSize = 200

        @optunity.cross_validated(x=X, y=Y, num_folds=n_folds)
        def f(x_train, y_train, x_test, y_test, gamma, forget, eta, nu):
            gamma = np.exp(gamma)
            return regression(x_train, y_train, x_test, y_test, gamma, forget, eta, nu, dictSize, RP=rij)

        return optunity.minimize(f, num_evals=n_evals, gamma=[-10,0], \
                                    forget=[0.98,1], eta=[0,0.5], nu=[0, 1], solver_name=solver )


    start_time = time.time()
    if ts == 0:
        print "     %d Fold CV   ..." %n_folds
        optimal_pars, info, _ = normal()
    else:
        optimal_pars, info, _ = time_series()
    end_time = time.time()

    optimal_pars['gamma'] = np.exp(optimal_pars['gamma'])

    print("\nOptimal Hyperparameters: " + str(optimal_pars))
    print "Optimum MSE: ", info.optimum
    print "time taken = %.2f secs"%(end_time-start_time)
  
    # saving params to file
    saveOutputFile("reg_optimised.json", optimal_pars, info.optimum)


def optimise_clas(d, solver='particle swarm', n_evals=3, task=3, ts=1, RP=False):
    # other solvers may be better {particle swarm, nelder-mead, sobol, random search, grid search}
    #print optunity.available_solvers() #for more info.
    n_folds = 5
    rij = None

    print "Solver:      ", solver
    print "Dataset:     ", d
    print "Task:        ", task
    print "TimeSeries:  ", ts!=0
    print "RandProj:    ", RP    

    if task != 1:
        raise TaskError(task, 1)

    if RP:
        rij,trans = generateRandomMatrix(mem_csv, seeds_csv)
        print "Proj:        ", trans  
    
    print "n_evals:     ", n_evals   
    print "\n     Solving     ..."

    def time_series():
        # the order of examples is dependent on time, and must be preserved
        # i.e. we can not randomly shuffle the data
        x_train, y_train, x_test, y_test = dataset_split(d, split=0.8)
        dictSize = 200

        def f(gamma, forget, eta, nu):
            #gamma = np.exp(gamma)
            return classification(x_train, y_train, x_test, y_test, gamma, forget, eta, nu, dictSize, RP=rij)

        return optunity.maximize(f, num_evals=n_evals, gamma=[0,1], \
                                    forget=[0.97,1], eta=[0,0.5], nu=[0, 1], solver_name=solver )
        


    def normal():
        # we use a different optimizer. one which we can do cross-validation.
        X, Y, _, _ = dataset_split(d, split=1)
        dictSize = 200

        @optunity.cross_validated(x=X, y=Y, num_folds=n_folds)
        def f(x_train, y_train, x_test, y_test, gamma, forget, eta, nu):
            gamma = np.exp(gamma)
            return classification(x_train, y_train, x_test, y_test, gamma, forget, eta, nu, dictSize, RP=rij)

        return optunity.maximize(f, num_evals=n_evals, gamma=[-10,0], \
                                    forget=[0.97,1], eta=[0, 0.5], nu=[0, 1], solver_name=solver )



    start_time = time.time()
    if ts == 0:
        print "     %d Fold CV   ..." %n_folds
        optimal_pars, info, _ = normal()
    else:
        optimal_pars, info, _ = time_series()
    end_time = time.time()

    #optimal_pars['gamma'] = np.exp(optimal_pars['gamma'])

    print("\nOptimal Hyperparameters: " + str(optimal_pars))
    print "Optimum AUC: ", info.optimum
    print "time taken = %.2f secs"%(end_time-start_time)

    
    # saving params to file
    saveOutputFile("clas_optimised.json", optimal_pars, info.optimum)


def optimise_nov(d, solver='particle swarm', n_evals=3, task=2, ts=1, RP=False):
    # other solvers may be better {particle swarm, nelder-mead, sobol, random search, grid search}
    #print optunity.available_solvers() #for more info.
    n_folds = 5
    rij = None

    print "Solver:      ", solver
    print "Dataset:     ", d
    print "Task:        ", task
    print "TimeSeries:  ", ts!=0
    print "RandProj:    ", RP    

    if task != 2:
        raise TaskError(task, 1)

    if RP:
        rij,trans = generateRandomMatrix(mem_csv, seeds_csv)
        print "Proj:        ", trans  
    
    print "n_evals:     ", n_evals   
    print "\n     Solving     ..."

    def time_series():
        # the order of examples is dependent on time, and must be preserved
        # i.e. we can not randomly shuffle the data
        x_train, y_train, x_test, y_test = dataset_split(d, split=2./3.)
        dictSize = 100

        def f(gamma, forget, eta, nu):
            return novelty_detection(x_train, y_train, x_test, y_test, gamma, forget, eta, nu, dictSize, RP=rij)

        return optunity.maximize(f, num_evals=n_evals, gamma=[0,1], \
                                    forget=[0.5,1], eta=[0,0.5], nu=[0, 1], solver_name=solver )
        

    def normal():
        # we use a different optimizer. one which we can do cross-validation.
        X, Y, _, _ = dataset_split(d, split=1)
        dictSize = 100

        @optunity.cross_validated(x=X, y=Y, num_folds=n_folds)
        def f(x_train, y_train, x_test, y_test, gamma, forget, eta, nu):
            return novelty_detection(x_train, y_train, x_test, y_test, gamma, forget, eta, nu, dictSize, RP=rij)

        return optunity.maximize(f, num_evals=n_evals, gamma=[0,1], \
                                    forget=[0.5,1], eta=[0, 0.5], nu=[0, 1], solver_name=solver )


    start_time = time.time()
    if ts == 0:
        print "     %d Fold CV   ..." %n_folds
        optimal_pars, info, _ = normal()
    else:
        optimal_pars, info, _ = time_series()
    end_time = time.time()

    print("\nOptimal Hyperparameters: " + str(optimal_pars))
    print "Optimum AUC: ", info.optimum
    print "time taken = %.2f secs"%(end_time-start_time)

    # saving params to file
    saveOutputFile("nov_optimised.json", optimal_pars, info.optimum)
    

def test_classification(d, task=1, RP=False):
    print "     Test"
    print "Dataset:     ", d
    print "Task:        ", task
    print "RandProj:    ", RP

    if task != 1:
        raise TaskError(task, 1)

    rij = None
    if RP:
        rij,trans = generateRandomMatrix(mem_csv, seeds_csv)
        print "Proj:        ", trans 

    x_train, y_train, x_test, y_test = dataset_split(d, split=0.8)

    auc = classification(x_train, y_train, x_test, y_test, params['gamma'], params['forget'], params['eta'], params['nu'], 200, RP=rij)
    print "\nAUC: ", auc


def test_novelty(d, task=2, RP=False):
    print "     Test"
    print "Dataset:     ", d
    print "Task:        ", task
    print "RandProj:    ", RP

    if task != 2:
        raise TaskError(task, 1)

    rij = None
    if RP:
        rij,trans = generateRandomMatrix(mem_csv, seeds_csv)
        print "Proj:        ", trans 

    x_train, y_train, x_test, y_test = dataset_split(d, split=2./3.)

    auc = novelty_detection(x_train, y_train, x_test, y_test, params['gamma'], params['forget'], params['eta'], params['nu'], 100, RP=rij)
    print "\nAUC: ", auc


def test_regression(d, task=3, RP=False):
    print "     Test"
    print "Dataset:     ", d
    print "Task:        ", task
    print "RandProj:    ", RP
    
    if task != 3:
        raise TaskError(task, 3)

    rij = None
    if RP:
        rij,trans = generateRandomMatrix(mem_csv, seeds_csv)
        print "Proj:        ", trans 

    x_train, y_train, x_test, y_test = dataset_split(d, split=0.8)
    
    mse = regression(x_train, y_train, x_test, y_test, params['gamma'], params['forget'], params['eta'], params['nu'], 200, RP=rij)
    print "\nMSE: ", mse



if __name__ == "__main__":


    try:
        dataset = sys.argv[1]
        func = sys.argv[2]  #test or opt
        appType = int(sys.argv[3])
        RP = int(sys.argv[4]) != 0
        
        if func == 'opt':
            ts = int(sys.argv[5])
            n_evals = int(sys.argv[6])

            if appType == 1:
                optimise_clas(dataset, n_evals=n_evals, task=appType, ts=ts, RP=RP)
            elif appType == 2:
                optimise_nov(dataset, n_evals=n_evals, task=appType, ts=ts, RP=RP)
            elif appType == 3:
                optimise_reg(dataset, n_evals=n_evals, task=appType, ts=ts, RP=RP)

        elif func == 'test':

            if appType == 1:
                test_classification(dataset, task=appType, RP=RP)
            elif appType == 2:
                test_novelty(dataset, task=appType, RP=RP)
            elif appType == 3:
                test_regression(dataset, task=appType, RP=RP)

        else:
            print "Error: 'test' or 'opt'"

    except IndexError:
		print "This script is used to test and optimise NORMA and VSRP+NORMA\n"
		print "run:	python vsrpnorma.py 1 2 3 4 *5 *6\n"
		print "1 - string 	'dataset_name.csv'"
		print "2 - string	'test' or 'opt'"
		print "3 - int 	Classification=1, Novelty Detection=2, Regression=3)"
		print "4 - int 	Random Projection? Yes=1, No=0"
		print "if 'opt':"
		print "5 - int:	Data as timeseries? Yes=1, No=0"
		print "6 - int:	Number of Evaluations\n"
		print notes_str    