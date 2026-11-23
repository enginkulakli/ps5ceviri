include(ExternalProject)

if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(OPENSSL_ANDROID_TARGET android-arm64)
elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
    set(OPENSSL_ANDROID_TARGET android-arm)
elseif(ANDROID_ABI STREQUAL "x86_64")
    set(OPENSSL_ANDROID_TARGET android-x86_64)
elseif(ANDROID_ABI STREQUAL "x86")
    set(OPENSSL_ANDROID_TARGET android-x86)
else()
    message(FATAL_ERROR "Unsupported Remote Play ABI: ${ANDROID_ABI}")
endif()

find_program(OPENSSL_MAKE NAMES gmake make REQUIRED)
get_filename_component(NDK_BIN "${CMAKE_C_COMPILER}" DIRECTORY)
set(OPENSSL_VERSION "3.5.9")
set(OPENSSL_PREFIX "${CMAKE_CURRENT_BINARY_DIR}/openssl")
set(OPENSSL_INCLUDE_DIR "${OPENSSL_PREFIX}/include")
set(OPENSSL_CRYPTO_LIBRARY "${OPENSSL_PREFIX}/lib/libcrypto.a")
set(OPENSSL_SSL_LIBRARY "${OPENSSL_PREFIX}/lib/libssl.a")
file(MAKE_DIRECTORY "${OPENSSL_INCLUDE_DIR}")

ExternalProject_Add(ps5-openssl
    URL https://github.com/openssl/openssl/releases/download/openssl-3.5.9/openssl-3.5.9.tar.gz
    URL_HASH SHA256=603f5602e2eef00d77fbd429d34dcd5822bb301757a1bc9cdb24c670f1eb859a
    INSTALL_DIR "${OPENSSL_PREFIX}"
    CONFIGURE_COMMAND ${CMAKE_COMMAND} -E env
        "ANDROID_NDK_ROOT=${ANDROID_NDK}" "PATH=${NDK_BIN}:$ENV{PATH}"
        <SOURCE_DIR>/Configure "${OPENSSL_ANDROID_TARGET}" "-D__ANDROID_API__=${ANDROID_PLATFORM_LEVEL}"
        --prefix=<INSTALL_DIR> --libdir=lib no-shared no-tests
    BUILD_COMMAND ${CMAKE_COMMAND} -E env "PATH=${NDK_BIN}:$ENV{PATH}" "${OPENSSL_MAKE}" -j2 build_libs
    INSTALL_COMMAND ${CMAKE_COMMAND} -E env "PATH=${NDK_BIN}:$ENV{PATH}" "${OPENSSL_MAKE}" install_dev
    BUILD_BYPRODUCTS "${OPENSSL_CRYPTO_LIBRARY}" "${OPENSSL_SSL_LIBRARY}"
)
add_library(OpenSSL::Crypto STATIC IMPORTED GLOBAL)
set_target_properties(OpenSSL::Crypto PROPERTIES
    IMPORTED_LOCATION "${OPENSSL_CRYPTO_LIBRARY}"
    INTERFACE_INCLUDE_DIRECTORIES "${OPENSSL_INCLUDE_DIR}")
add_dependencies(OpenSSL::Crypto ps5-openssl)
add_library(OpenSSL::SSL STATIC IMPORTED GLOBAL)
set_target_properties(OpenSSL::SSL PROPERTIES
    IMPORTED_LOCATION "${OPENSSL_SSL_LIBRARY}"
    INTERFACE_LINK_LIBRARIES OpenSSL::Crypto)
add_dependencies(OpenSSL::SSL ps5-openssl)
set(OPENSSL_FOUND TRUE)
set(OpenSSL_FOUND TRUE)
set(CHIAKI_LIB_OPENSSL_EXTERNAL_PROJECT ON)
add_library(OpenSSL_Crypto INTERFACE)
target_link_libraries(OpenSSL_Crypto INTERFACE OpenSSL::SSL OpenSSL::Crypto)
