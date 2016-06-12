RateLimitJ
============

[![Build Status](https://travis-ci.org/mokies/ratelimitj.svg)](https://travis-ci.org/mokies/ratelimitj)

Currently under active construction

Feature Roadmap
---------------

| Feature       | Status      |
| ------------- |-------------| 
| Redis sliding window rate limiter | alpha  |
| In memory sliding window rate limiter | not started |
| Hazelcast sliding window rate limiter | not started |
| Redis fast bucket rate limiter | not started |
| In memory fast bucket rate limiter | not started |
| In memory fast bucket rate limiter | not started |
| Dropwizard glue - bundle | active development |
| Spring glue | not started |



Background
----------
This library was inspired by the following articles on sliding window rate limiting with Redis:

* [Introduction to Rate Limiting with Redis Part 1](http://www.dr-josiah.com/2014/11/introduction-to-rate-limiting-with.html)
* [Introduction to Rate Limiting with Redis Part 2](http://www.dr-josiah.com/2014/11/introduction-to-rate-limiting-with_26.html)

For more information on the `weight` and `precision` options, see the second blog post above.

Inspired by [ratelimit.js](https://github.com/dudleycarr/ratelimit.js)

Authors
-------

* [Craig Baker](https://github.com/mokies)