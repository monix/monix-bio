To run all benchmarks with default parameters:

``` 
benchmarks/jmh:run
```

To use your own:

``` 
benchmarks/jmh:run -i 20 -wi 10 -f 3 -r 10 -t 1
```

It might be also useful to redirect results to a file:

``` 
benchmarks/jmh:run -i 20 -wi 10 -f 3 -r 10 -t 1 -rff "benchmarksResult.txt"
```

Refer to the full list of available parameters [here](https://github.com/guozheng/jmh-tutorial/blob/master/README.md).