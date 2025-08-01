#!/bin/bash

# Path to JSON libraries
CLASSPATH="javax.json-1.1.4.jar:."

# Create the bin directory if it doesn't exist
mkdir -p bin

# Compile the Java files and output .class files to the bin directory
echo "Compiling Java files..."
javac -cp "$CLASSPATH" -d bin *.java

# Check if javac was successful
if [ $? -eq 0 ]; then
  echo "Compilation successful. Moving .class files to bin directory..."

  # Move all .class files to the bin directory (in case they're generated elsewhere)
  mv *.class bin/

  # Run the server from the bin directory
  echo "Starting the server..."
  java -cp "$CLASSPATH:bin" ChirpServer
else
  echo "Compilation failed. Exiting..."
  exit 1
fi
