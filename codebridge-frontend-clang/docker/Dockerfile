FROM ubuntu:24.04
ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends clang libclang-dev llvm-dev nlohmann-json3-dev build-essential && rm -rf /var/lib/apt/lists/*
COPY main.cpp /tmp/main.cpp
RUN clang++ -std=c++20 -I"$(llvm-config --includedir)" /tmp/main.cpp -L"$(llvm-config --libdir)" -Wl,-rpath,"$(llvm-config --libdir)" -lclang -o /usr/local/bin/codebridge-clang-front && rm /tmp/main.cpp
USER 65532:65532
ENTRYPOINT ["/usr/local/bin/codebridge-clang-front"]
