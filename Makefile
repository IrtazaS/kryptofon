
SOURCES=src/*/*.java src/*.java
TARGET=kryptofon.jar

all: $(TARGET)

$(TARGET): $(SOURCES)
	jar cvfm $(TARGET) manifest.txt resources/ -C bin . 

doxy:
	doxygen doxy.cfg
	
clean:
	rm -rf $(TARGET)
