FROM jetty:9.3-jre8

ADD . /

RUN java -jar "$JETTY_HOME/start.jar" --add-to-startd=jmx,jmx-remote,stats

CMD ["java","-Djava.io.tmpdir=/tmp/jetty", "-Dgeorchestra.datadir=/etc/georchestra","-jar","/usr/local/jetty/start.jar"]

