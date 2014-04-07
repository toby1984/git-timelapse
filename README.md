git-timelapse V0.1
==================

A tiny Swing application that displays GIT revisions of a file as a side-by-side diff along with a slider to browse through revisions in temporal order.

This project was inspired by the awesome http://code.google.com/p/svn-time-lapse-view that I used quite a lot back in the days when SVN was the best SCM around :)

Disclaimer
----------

Note that this is an **ALPHA VERSION** and diff highlighting/alignment is somewhat quirky, I *strongly* recommend using a proper diff viewer / gitk when doing serious work. Don't complain if my program eats your lunch or crashes your machine - you have been warned.

Known issues
------------

- Executing with a non-versioned file fails with a bogus error message
- Invoking the tool with a versioned resource in directory /foo while not being in the same subtree as the GIT repository that contains /foo will not work
- The program is supposed to keep track of the currently visible text region across different revisions ; this is currently not working correctly and the view will always jump to the end of the file instead

Building
--------

Requires Maven >= 2.2.1 and JDK >= 1.7

Just run

    mvn clean package

and you should find a self-executable JAR named 'git-timelapse.jar' inside the /target folder.

Usage
-----

    java -jar git-timelapse.jar <path to file under GIT version control>


