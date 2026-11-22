package com.blockbrowser.app.extensions

import android.content.Context
import android.content.SharedPreferences
import android.webkit.JavascriptInterface
import org.json.JSONObject
import java.io.File

class WebExtensionBridge(
    private val context: Context,
    private val extension: InstalledXpiExtension,
    private val onOpenUrl: (String) -> Unit
) {

    @JavascriptInterface
    fun saveStorage(json: String) {
        saveStorageJson(context, extension.id, json)
    }

    @JavascriptInterface
    fun openUrl(url: String) {
        onOpenUrl(url)
    }

    @JavascriptInterface
    fun onExtensionMessage(json: String): String {
        // Echo back or handle basic extension messages
        return JSONObject().put("status", "ok").toString()
    }

    companion object {
        private const val PREFS = "block_browser_ext_storage"

        fun getStorageJson(context: Context, extId: String): String {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return prefs.getString(extId, "{}") ?: "{}"
        }

        fun saveStorageJson(context: Context, extId: String, json: String) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.edit().putString(extId, json).apply()
        }

        fun loadLocales(extDir: File): String {
            val localesDir = File(extDir, "_locales")
            if (!localesDir.exists()) return "{}"

            val deFile = File(localesDir, "de/messages.json")
            val enFile = File(localesDir, "en/messages.json")
            val enUsFile = File(localesDir, "en_US/messages.json")

            val targetFile = when {
                deFile.exists() -> deFile
                enFile.exists() -> enFile
                enUsFile.exists() -> enUsFile
                else -> localesDir.walkTopDown().firstOrNull { it.name.equals("messages.json", ignoreCase = true) }
            }

            return targetFile?.readText() ?: "{}"
        }

        fun getBridgeScript(
            context: Context,
            extension: InstalledXpiExtension,
            currentPageUrl: String,
            baseUrl: String
        ): String {
            val storageJson = getStorageJson(context, extension.id)
            val extDir = File(context.filesDir, "xpi_extensions/${extension.id}")
            val localesJson = loadLocales(extDir)

            // Safely prepare JSON literals
            val safeStorage = storageJson.replace("`", "\\`")
            val safeLocales = localesJson.replace("`", "\\`")

            return """
                (function() {
                    if (window.browser && window.browser._isPolyfilled) return;

                    window.__EXT_ID = "${extension.id}";
                    window.__CURRENT_PAGE_URL = "$currentPageUrl";
                    window.__EXT_BASE_URL = "$baseUrl";
                    
                    try {
                        window.__EXT_STORAGE = JSON.parse(`$safeStorage`);
                    } catch(e) {
                        window.__EXT_STORAGE = {};
                    }
                    
                    try {
                        window.__EXT_MESSAGES = JSON.parse(`$safeLocales`);
                    } catch(e) {
                        window.__EXT_MESSAGES = {};
                    }

                    const _storageData = window.__EXT_STORAGE;

                    function persistStorage() {
                        try {
                            const json = JSON.stringify(_storageData);
                            localStorage.setItem('__webext_storage', json);
                            if (window.AndroidWebExt) {
                                window.AndroidWebExt.saveStorage(json);
                            }
                        } catch(e) {}
                    }

                    const storageLocal = {
                        get: function(keys, callback) {
                            return new Promise((resolve) => {
                                let result = {};
                                if (!keys) {
                                    result = Object.assign({}, _storageData);
                                } else if (typeof keys === 'string') {
                                    result[keys] = _storageData[keys];
                                } else if (Array.isArray(keys)) {
                                    keys.forEach(k => { result[k] = _storageData[k]; });
                                } else if (typeof keys === 'object') {
                                    Object.keys(keys).forEach(k => {
                                        result[k] = _storageData[k] !== undefined ? _storageData[k] : keys[k];
                                    });
                                }
                                if (callback) callback(result);
                                resolve(result);
                            });
                        },
                        set: function(items, callback) {
                            return new Promise((resolve) => {
                                if (typeof items === 'object') {
                                    Object.assign(_storageData, items);
                                    persistStorage();
                                }
                                if (callback) callback();
                                resolve();
                            });
                        },
                        remove: function(keys, callback) {
                            return new Promise((resolve) => {
                                const arr = Array.isArray(keys) ? keys : [keys];
                                arr.forEach(k => delete _storageData[k]);
                                persistStorage();
                                if (callback) callback();
                                resolve();
                            });
                        },
                        clear: function(callback) {
                            return new Promise((resolve) => {
                                for (let k in _storageData) delete _storageData[k];
                                persistStorage();
                                if (callback) callback();
                                resolve();
                            });
                        }
                    };

                    const listeners = [];
                    const runtime = {
                        id: window.__EXT_ID,
                        getURL: function(path) {
                            if (!path) return '';
                            if (path.startsWith('http://') || path.startsWith('https://') || path.startsWith('file://')) return path;
                            return window.__EXT_BASE_URL + path.replace(/^\//, '');
                        },
                        getManifest: function() {
                            return {
                                manifest_version: 2,
                                name: "${extension.name}",
                                version: "${extension.version}",
                                description: "${extension.description}"
                            };
                        },
                        sendMessage: function(message, callback) {
                            return new Promise((resolve) => {
                                if (window.AndroidWebExt) {
                                    const resp = window.AndroidWebExt.onExtensionMessage(JSON.stringify(message));
                                    try {
                                        const parsed = JSON.parse(resp);
                                        if (callback) callback(parsed);
                                        resolve(parsed);
                                        return;
                                    } catch(e) {}
                                }
                                if (callback) callback({});
                                resolve({});
                            });
                        },
                        onMessage: {
                            addListener: function(fn) { listeners.push(fn); },
                            removeListener: function(fn) {
                                const idx = listeners.indexOf(fn);
                                if (idx > -1) listeners.splice(idx, 1);
                            }
                        }
                    };

                    const i18n = {
                        getMessage: function(messageName, substitutions) {
                            if (window.__EXT_MESSAGES && window.__EXT_MESSAGES[messageName]) {
                                return window.__EXT_MESSAGES[messageName].message || messageName;
                            }
                            return messageName;
                        },
                        getUILanguage: function() { return navigator.language || 'de'; }
                    };

                    const tabs = {
                        query: function(queryInfo, callback) {
                            return new Promise((resolve) => {
                                const res = [{ id: 1, active: true, url: window.__CURRENT_PAGE_URL || location.href, title: document.title }];
                                if (callback) callback(res);
                                resolve(res);
                            });
                        },
                        create: function(createProperties, callback) {
                            return new Promise((resolve) => {
                                if (createProperties && createProperties.url && window.AndroidWebExt) {
                                    window.AndroidWebExt.openUrl(createProperties.url);
                                }
                                const res = { id: 2, url: createProperties ? createProperties.url : '' };
                                if (callback) callback(res);
                                resolve(res);
                            });
                        },
                        sendMessage: function(tabId, message, callback) {
                            return new Promise((resolve) => {
                                if (callback) callback({});
                                resolve({});
                            });
                        }
                    };

                    const browserObj = {
                        _isPolyfilled: true,
                        storage: {
                            local: storageLocal,
                            sync: storageLocal,
                            managed: storageLocal
                        },
                        runtime: runtime,
                        i18n: i18n,
                        tabs: tabs,
                        extension: {
                            getURL: runtime.getURL
                        }
                    };

                    window.browser = browserObj;
                    window.chrome = browserObj;
                })();
            """.trimIndent()
        }
    }
}
