FROM openjdk:11
MAINTAINER yangkun yangkun@sefon.com
#WORKDIR /ROOT
VOLUME /tmp
ADD target/047-springboot-dubbo-ssm-consumer-1.0-SNAPSHOT.jar 047-springboot-dubbo-ssm-consumer-1.0-SNAPSHOT.jar
ENTRYPOINT ["java", "-Djava.security.egd=file:/dev/./urandom", "-jar", "047-springboot-dubbo-ssm-consumer-1.0-SNAPSHOT.jar"]
