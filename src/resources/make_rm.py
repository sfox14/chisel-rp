import numpy as np
import sys
import csv
import pandas as pd

def getSeeds(n_features, n_components, seeds_file, mem_file):
    # generate a csv file for the random seeds
    # 16bits

    rng = np.random.RandomState(3679)
    vals = rng.get_state()[1]

    other = np.random.permutation(vals[1:])[:n_components-1]

    seeds = [vals[0]]
    for val in other:
        seeds.append(int(val>>16))

    myfile = open(seeds_file, 'wb')
    wr = csv.writer(myfile)
    wr.writerow(seeds)


def getNonZeros(n_features, n_components, seeds_file, mem_file):

    less_sparse = False

    # generate non zeros (n_features x n_components)
    f = open(seeds_file, 'rb')
    reader = csv.reader(f)
    seeds = list(reader)[0]
    
    if less_sparse:
        print "Less-Sparse Random Projection"
        density = 1./np.sqrt(n_features)
    else:
        print "Very-Sparse Random Projection"
        density = 1./n_components
    
    c=0 # number of non zero entries
    non_zeros = []
    for i in range(n_components):
        
        seed = int(seeds[i])
        rng = np.random.RandomState(seed)

        a = rng.binomial(n_features, density) #(1./n_components)) ##key area here, could 
        indices = []
        for k in range(a):
            d = rng.randint(0, n_features)
            if d in indices:
                proc = True
                while proc is True:
                    a=rng.randint(0, n_features)
                    if a != d:
                        proc = False
                indices.append(a)
            else:
                indices.append(d)

        non_zeros.append(indices)
        c+=len(indices)

    mem = np.zeros((n_components, n_features), dtype=bool) # or bool
    print "Shape of matrix: ", mem.T.shape

    for i, elem in enumerate(non_zeros):
        print i, elem
        for pos in elem:
            mem[i][pos] = True

    mem = mem.T
    np.savetxt(mem_file, mem, fmt="%s", delimiter=",")

    print "Average number of non-zero's per component (i.e. column): ", float(c/float(n_components))

    if less_sparse:
        print "Coefficient to multiply: ", np.sqrt(float(np.sqrt(n_features)/n_components)) 


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


# must only be run after genSeeds() and genNonZeros()
def generateRandomMatrix(seeds_file, mem_file):
    # load random matrix from data/mem.csv and seeds.csv
    x = pd.DataFrame.from_csv(seeds_file, index_col=False, header=None).values.astype(int)
    seeds = pd.DataFrame.from_csv(mem_file, index_col=False, header=None).values.astype(int).reshape(-1,)
    
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

    print("Very Sparse Random Matrix: \n", rij)
    return rij, x.shape


if __name__ == "__main__":	


    nf = int(sys.argv[1])
    nc = int(sys.argv[2])

    seeds_file = "./seeds.csv"
    mem_file = "./mem.csv"

    getSeeds(nf, nc, seeds_file, mem_file)
    getNonZeros(nf, nc, seeds_file, mem_file)
    generateRandomMatrix(seeds_file, mem_file)
