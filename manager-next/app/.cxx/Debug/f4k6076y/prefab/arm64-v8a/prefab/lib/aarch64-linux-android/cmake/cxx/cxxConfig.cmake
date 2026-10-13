if(NOT TARGET cxx::cxx)
add_library(cxx::cxx STATIC IMPORTED)
set_target_properties(cxx::cxx PROPERTIES
    IMPORTED_LOCATION "/Users/7kimimasy/.gradle/caches/9.7.1/transforms/6d312d6cb720be371d61c4122877a161/transformed/libcxx-29.0.14206865/prefab/modules/cxx/libs/android.arm64-v8a/libcxx.a"
    INTERFACE_INCLUDE_DIRECTORIES "/Users/7kimimasy/.gradle/caches/9.7.1/transforms/6d312d6cb720be371d61c4122877a161/transformed/libcxx-29.0.14206865/prefab/modules/cxx/include"
    INTERFACE_LINK_LIBRARIES ""
)
endif()

