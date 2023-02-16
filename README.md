# nem-scraper

This application is designed continually poll nemweb for energy price data, my seletronic battery charger controller for battery info, and my solarmax inverter for solar data.
Note all of these interfaces were reverse engineered from the manufacturers various products (some now defunct), so they may or may not work in your installation (depending on versions/ models etc)
 

## Installation

Download from https://github.com/pdmct/nem-scraper

There are a bunch of passwords, local ip addresses etc in the code that you will need to fix up to make this run. 

You will also need a redis database with REDIS TIMESERIES installed in it.

NB: I wrote this a while ago and it still uses a bunch of old versions libraries. Sorry. I might update sometime.

## Usage

This can be built into a jar file and run in the background, as a service or something.

To run the code as is you can just nohup it from the cmdline:

    $ nohup clojure -M -m pdmct.nem-scraper output.csv &

Build an uberjar:

    $ clojure -X:uberjar

This will update the generated `pom.xml` file to keep the dependencies synchronized with
your `deps.edn` file. You can update the version (and SCM tag) information in the `pom.xml` using the
`:version` argument:

    $ clojure -X:uberjar :version '"1.2.3"'

If you don't want the `pom.xml` file in your project, you can remove it, but you will
also need to remove `:sync-pom true` from the `deps.edn` file (in the `:exec-args` for `depstar`).

Run that uberjar:

    $ java -jar nem-scraper.jar

## License
EPL v1.0

Copyright © 2021 Peter McTaggart

_EPLv1.0 is just the default for projects generated by `clj-new`: you are not_
_required to open source this project, nor are you required to use EPLv1.0!_
_Feel free to remove or change the `LICENSE` file and remove or update this_
_section of the `README.md` file!_

Distributed under the Eclipse Public License version 1.0.
