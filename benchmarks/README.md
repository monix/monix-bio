To run all benchmarks with default parameters:

``` 
benchmarks/jmh:run
```

To use your own:

``` 
benchmarks/jmh:run -i 20 -wi 10 -f 3 -r 10 -t 1
```

To redirect results to a file:

``` 
benchmarks/jmh:run -i 20 -wi 10 -f 3 -r 10 -t 1 -rff "benchmarksResult.txt"
```

Refer to the full list of available parameters [here](https://github.com/guozheng/jmh-tutorial/blob/master/README.md).

### Results

DISCLAIMER: Benchmarks do not necessarily reflect the performance in real conditions so take the results with a grain with salt. 
It is possible that in your specific use different effects will perform differently than the benchmark would suggest.
It is best to always measure yourself and for your specific use case if you are concerned about the performance.

- [28-06-2020](results/28-06-2020.md)
- [10-12-2019](results/10-12-2019.md)