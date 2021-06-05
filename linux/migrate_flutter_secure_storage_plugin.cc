#include "include/migrate_flutter_secure_storage/migrate_flutter_secure_storage_plugin.h"
#include "include/Secret.hpp"

#include <cstring>
#include <flutter_linux/flutter_linux.h>
#include <gtk/gtk.h>
#include <json/json.h>
#include <libsecret/secret.h>
#include <sys/utsname.h>

#define MIGRATE_FLUTTER_SECURE_STORAGE_PLUGIN(obj)                                     \
  (G_TYPE_CHECK_INSTANCE_CAST((obj), migrate_flutter_secure_storage_plugin_get_type(), \
                              MigrateFlutterSecureStoragePlugin))

struct _MigrateFlutterSecureStoragePlugin {
  GObject parent_instance;
};

G_DEFINE_TYPE(MigrateFlutterSecureStoragePlugin, migrate_flutter_secure_storage_plugin,
              g_object_get_type())

static SecretStorage keyring;
void deleteIt(const gchar *key) { keyring.deleteItem(key); }
void deleteAll() { keyring.deleteKeyring(); }

void write(const gchar *key, const gchar *value) {
  keyring.addItem(key, value);
}

FlValue *read(const gchar *key) {
  auto str = keyring.getItem(key);
  if (str == "") {
    return nullptr;
  }
  return fl_value_new_string(str.c_str());
}

FlValue *readAll() {
  FlValue *result = fl_value_new_map();
  Json::Value data = keyring.readFromKeyring();
  for (auto each : data.getMemberNames()) {
    fl_value_set_string(result, each.c_str(),
                        fl_value_new_string(data[each].asCString()));
  }
  return result;
}

// Called when a method call is received from Flutter.
static void migrate_flutter_secure_storage_plugin_handle_method_call(
    MigrateFlutterSecureStoragePlugin *self, FlMethodCall *method_call) {
  g_autoptr(FlMethodResponse) response = nullptr;

  const gchar *method = fl_method_call_get_name(method_call);
  FlValue *args = fl_method_call_get_args(method_call);

  if (fl_value_get_type(args) != FL_VALUE_TYPE_MAP) {
    response = FL_METHOD_RESPONSE(fl_method_error_response_new(
        "Bad arguments", "args given to function is not a map", nullptr));
  } else {
    FlValue *key = fl_value_lookup_string(args, "key");
    FlValue *value = fl_value_lookup_string(args, "value");
    const gchar *keyString =
        key == nullptr ? nullptr : fl_value_get_string(key);
    const gchar *valueString =
        value == nullptr ? nullptr : fl_value_get_string(value);

    try {
      if (strcmp(method, "write") == 0) {
        if (!keyString || !valueString) {
          response = FL_METHOD_RESPONSE(fl_method_error_response_new(
              "Bad arguments", "Key or Value was null", nullptr));
        } else {
          write(keyString, valueString);
          response =
              FL_METHOD_RESPONSE(fl_method_success_response_new(nullptr));
        }
      } else if (strcmp(method, "read") == 0) {
        if (!keyString) {
          response = FL_METHOD_RESPONSE(fl_method_error_response_new(
              "Bad arguments", "Key is null", nullptr));
        } else {
          g_autoptr(FlValue) result = read(keyString);
          response = FL_METHOD_RESPONSE(fl_method_success_response_new(result));
        }
      } else if (strcmp(method, "readAll") == 0) {
        response =
            FL_METHOD_RESPONSE(fl_method_success_response_new(readAll()));
      } else if (strcmp(method, "delete") == 0) {
        if (!keyString) {
          response = FL_METHOD_RESPONSE(fl_method_error_response_new(
              "Bad arguments", "Key is null", nullptr));
        } else {
          deleteIt(keyString);
          response =
              FL_METHOD_RESPONSE(fl_method_success_response_new(nullptr));
        }
      } else if (strcmp(method, "deleteAll") == 0) {
        deleteAll();
        response = FL_METHOD_RESPONSE(fl_method_success_response_new(nullptr));
      } else {
        response = FL_METHOD_RESPONSE(fl_method_not_implemented_response_new());
      }
    } catch (const gchar* e) {
      g_warning("libsecret_error: %s", e);
      response = FL_METHOD_RESPONSE(
          fl_method_error_response_new("Libsecret error", e, nullptr));
    } 
    fl_method_call_respond(method_call, response, nullptr);
  }
}

static void migrate_flutter_secure_storage_plugin_dispose(GObject *object) {
  G_OBJECT_CLASS(migrate_flutter_secure_storage_plugin_parent_class)->dispose(object);
}

static void migrate_flutter_secure_storage_plugin_class_init(
    MigrateFlutterSecureStoragePluginClass *klass) {
  G_OBJECT_CLASS(klass)->dispose = migrate_flutter_secure_storage_plugin_dispose;
}

static void
migrate_flutter_secure_storage_plugin_initMigrateFlutterSecureStoragePlugin *self) {}

static void method_call_cb(FlMethodChannel *channel, FlMethodCall *method_call,
                           gpointer user_data) {
  FlutterSecureStoragePlugin *plugin = MIGRATE_FLUTTER_SECURE_STORAGE_PLUGIN(user_data);
  migrate_flutter_secure_storage_plugin_handle_method_call(plugin, method_call);
}

void migrate_flutter_secure_storage_plugin_register_with_registrar(
    FlPluginRegistrar *registrar) {
  MigrateFlutterSecureStoragePlugin *plugin = MIGRATE_FLUTTER_SECURE_STORAGE_PLUGIN(
      g_object_new(migrate_flutter_secure_storage_plugin_get_type(), nullptr));

  g_autoptr(FlStandardMethodCodec) codec = fl_standard_method_codec_new();
  g_autoptr(FlMethodChannel) channel = fl_method_channel_new(
      fl_plugin_registrar_get_messenger(registrar),
      "plugins.harkertech.com/migrate_flutter_secure_storage", FL_METHOD_CODEC(codec));
  fl_method_channel_set_method_call_handler(
      channel, method_call_cb, g_object_ref(plugin), g_object_unref);
  const gchar *label =
      g_strdup_printf("%s/MigrateFlutterSecureStorage", APPLICATION_ID);
  const gchar *account = g_strdup_printf("%s.secureStorage", APPLICATION_ID);
  keyring.setLabel(label);
  keyring.addAttribute("account", account);
  g_object_unref(plugin);
}