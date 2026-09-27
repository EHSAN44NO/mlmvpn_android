// Device-side parser/crypto smoke test. Never connects or accepts credentials.
#include "bridge.cpp"
#include <fstream>
#include <sstream>
#include <iostream>
int main(int argc, char** argv) {
    if (argc != 2) return 2;
    std::ifstream file(argv[1]);
    if (!file) return 3;
    std::stringstream content;
    content << file.rdbuf();
    try {
        Client client;
        auto result = client.eval_config(configuration(content.str()));
        if (result.error) { std::cout << "FAIL: " << result.message << '\n'; return 1; }
        std::cout << "PASS: " << result.remoteHost << ':' << result.remotePort << ' ' << result.remoteProto << '\n';
        return 0;
    } catch (const std::exception& e) { std::cout << "FAIL: " << e.what() << '\n'; return 1; }
}
