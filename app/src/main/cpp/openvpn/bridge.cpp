#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <cctype>
#include <atomic>
#include <memory>
#include <mutex>
#include <unordered_map>
#include <openvpn/io/io.hpp>
#include <client/ovpncli.hpp>
#include <openvpn/client/dns_options.hpp>

namespace api = openvpn::ClientAPI;
namespace {
std::string utf(JNIEnv* env, jstring value) {
    if (!value) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) throw std::runtime_error("UTF conversion failed");
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

class Client final : public api::OpenVPNClient {
public:
    JNIEnv* env = nullptr;
    jobject callback = nullptr; // local ref remains alive throughout nativeRun
    jclass cls = nullptr;
    std::atomic<bool> cancelled{false};

    bool call(const char* name, const std::string& value, int number = 0) {
        if (!callback) return false;
        jstring text = env->NewStringUTF(value.c_str());
        bool result = env->CallBooleanMethod(callback,
            env->GetMethodID(cls, name, "(Ljava/lang/String;I)Z"), text, number);
        env->DeleteLocalRef(text);
        if (env->ExceptionCheck()) { env->ExceptionClear(); return false; }
        return result;
    }
    void sendEvent(const std::string& name, bool error) {
        if (!callback) return;
        auto text = env->NewStringUTF(name.c_str());
        env->CallVoidMethod(callback, env->GetMethodID(cls, "onCoreEvent", "(Ljava/lang/String;Z)V"), text, error);
        env->DeleteLocalRef(text);
        if (env->ExceptionCheck()) { env->ExceptionClear(); cancelled = true; stop(); }
    }
    bool tun_builder_new() override { return call("configureTun", "new"); }
    bool tun_builder_add_address(const std::string& address, int prefix, const std::string&, bool, bool) override {
        return call("addAddress", address, prefix);
    }
    bool tun_builder_add_route(const std::string& address, int prefix, int, bool) override {
        return call("addRoute", address, prefix);
    }
    bool tun_builder_reroute_gw(bool v4, bool v6, unsigned int) override {
        return (!v4 || call("addRoute", "0.0.0.0", 0)) && (!v6 || call("addRoute", "::", 0));
    }
    bool tun_builder_set_remote_address(const std::string&, bool) override { return true; }
    bool tun_builder_set_mtu(int mtu) override { return call("configureTun", "mtu", mtu); }
    bool tun_builder_set_session_name(const std::string&) override { return true; }
    bool tun_builder_set_dns_options(const openvpn::DnsOptions& dns) override {
        for (const auto& [priority, server] : dns.servers) {
            for (const auto& addr : server.addresses) {
                if (addr.port && addr.port != 53) return false;
                if (!call("addDns", addr.address)) return false;
            }
        }
        for (const auto& domain : dns.search_domains) if (!call("addSearchDomain", domain.domain)) return false;
        return true;
    }
    int tun_builder_establish() override {
        if (cancelled) return -1;
        int fd = env->CallIntMethod(callback, env->GetMethodID(cls, "establishTun", "()I"));
        if (env->ExceptionCheck()) { env->ExceptionClear(); return -1; }
        return fd;
    }
    bool socket_protect(openvpn_io::detail::socket_type fd, std::string, bool) override {
        return call("configureTun", "protect", fd);
    }
    bool pause_on_connection_timeout() override { return false; }
    void event(const api::Event& event) override { sendEvent(event.name, event.error || event.fatal); }
    void acc_event(const api::AppCustomControlMessageEvent&) override {}
    // The core's own log, scrubbed: without it a failed connect was a bare "timeout" with no
    // stage. Lines that could carry a profile's inline material or a credential are dropped
    // whole, never masked -- a mask that misses one form of a secret leaks it.
    void log(const api::LogInfo& info) override {
        std::string line = info.text;
        while (!line.empty() && (line.back() == '\n' || line.back() == '\r')) line.pop_back();
        if (line.empty() || line.size() > 400) return;
        std::string lower = line;
        std::transform(lower.begin(), lower.end(), lower.begin(), [](unsigned char c) { return std::tolower(c); });
        for (const char* secret : {"password", "passwd", "auth-user-pass", "-----", "<ca>", "<cert>", "<key>",
                                   "<tls", "private", "username", "session-id", "token"}) {
            if (lower.find(secret) != std::string::npos) return;
        }
        __android_log_print(ANDROID_LOG_INFO, "OpenVpnCore", "%s", line.c_str());
    }
    void external_pki_cert_request(api::ExternalPKICertRequest& r) override { r.error = true; }
    void external_pki_sign_request(api::ExternalPKISignRequest& r) override { r.error = true; }
    void connect_pre_run() override { if (cancelled) stop(); }
    void clock_tick() override {
        if (cancelled) { stop(); return; }
        auto stats = tun_stats();
        env->CallVoidMethod(callback, env->GetMethodID(cls, "onCoreTraffic", "(JJ)V"),
            static_cast<jlong>(stats.bytesOut), static_cast<jlong>(stats.bytesIn));
        if (env->ExceptionCheck()) { env->ExceptionClear(); cancelled = true; stop(); }
    }
};

std::mutex clientsMutex;
std::unordered_map<jlong, std::shared_ptr<Client>> clients;
std::atomic<jlong> sequence{1};
std::shared_ptr<Client> find(jlong id) {
    std::lock_guard<std::mutex> lock(clientsMutex);
    const auto it = clients.find(id);
    return it == clients.end() ? nullptr : it->second;
}
api::Config configuration(std::string content) {
    api::Config config;
    config.content = std::move(content);
    config.guiVersion = "MLMVPN 1";
    config.connTimeout = 40;
    config.clockTickMS = 1000;
    config.tunPersist = true;
    config.retryOnAuthFailed = false;
    // TunnelBear pushes `comp-lzo`-style compression; the default ("no") ends the session right
    // after "Connected via tun". "asym" accepts compressed packets from the server but never
    // compresses ours, which keeps the VORACLE class of leaks shut on what we send.
    config.compressionMode = "asym";
    config.disableClientCert = config.content.find("<cert>") == std::string::npos;
    return config;
}
}

#define JNI_METHOD(name) Java_com_mlmvpn_scanner_openvpn_OpenVpnNative_##name
extern "C" JNIEXPORT jint JNICALL JNI_METHOD(apiVersion)(JNIEnv*, jobject) { return 1; }
extern "C" JNIEXPORT jstring JNICALL JNI_METHOD(evaluate)(JNIEnv* env, jobject, jstring text) {
    try {
        Client client;
        auto result = client.eval_config(configuration(utf(env, text)));
        // Return a fixed code: eval messages can contain inline secrets from a malformed profile.
        return env->NewStringUTF(result.error ? "PROFILE_UNSUPPORTED" : "");
    } catch (...) { return env->NewStringUTF("PROFILE_INVALID"); }
}
extern "C" JNIEXPORT jlong JNICALL JNI_METHOD(create)(JNIEnv*, jobject) {
    try {
        auto client = std::make_shared<Client>();
        auto id = sequence++;
        std::lock_guard<std::mutex> lock(clientsMutex);
        clients.emplace(id, std::move(client));
        return id;
    } catch (...) { return 0; }
}
extern "C" JNIEXPORT void JNICALL JNI_METHOD(stop)(JNIEnv*, jobject, jlong id) {
    if (auto client = find(id)) { client->cancelled = true; client->stop(); }
}
extern "C" JNIEXPORT void JNICALL JNI_METHOD(release)(JNIEnv*, jobject, jlong id) {
    std::lock_guard<std::mutex> lock(clientsMutex);
    clients.erase(id);
}
extern "C" JNIEXPORT jstring JNICALL JNI_METHOD(run)(JNIEnv* env, jobject, jlong id, jstring text,
    jstring user, jstring password, jobject callback) {
    auto client = find(id);
    if (!client) return env->NewStringUTF("CORE_UNAVAILABLE");
    client->env = env;
    client->callback = callback;
    client->cls = env->GetObjectClass(callback);
    std::string error;
    try {
        auto config = configuration(utf(env, text));
        auto evaluation = client->eval_config(config);
        if (evaluation.error) error = "PROFILE_UNSUPPORTED";
        else if (!client->cancelled) {
            api::ProvideCreds creds;
            creds.username = utf(env, user);
            creds.password = utf(env, password);
            auto status = client->provide_creds(creds);
            if (status.error) error = "CREDENTIALS_REJECTED";
            else if (!client->cancelled) {
                auto result = client->connect();
                if (result.error && !client->cancelled) error = "CONNECTION_FAILED";
            }
        }
    } catch (...) { error = "CORE_ERROR"; }
    env->DeleteLocalRef(client->cls);
    client->callback = nullptr;
    return env->NewStringUTF(error.c_str());
}
