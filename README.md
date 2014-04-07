git-timelapse V0.1
==================

A tiny Swing application that displays GIT revisions of a file as a side-by-side diff along with a slider to browse through revisions in temporal order.

This project was inspired by the awesome http://code.google.com/p/svn-time-lapse-view that I used quite a lot back in the days when SVN was the best SCM around :)

Disclaimer
----------

Note that this is an **ALPHA VERSION** and diff highlighting/alignment is somewhat quirky, I *strongly* recommend using a proper diff viewer / gitk when doing serious work. Don't complain if my program eats your lunch or crashes your machine - you have been warned.

Building
--------

Requires Maven >= 2.2.1 and JDK >= 1.7

Just run

    mvn clean package

and you should find a self-executable JAR named 'git-timelapse.jar' inside the /target folder.

Usage
-----

    java -jar git-timelapse.jar <path to file under GIT version control>


