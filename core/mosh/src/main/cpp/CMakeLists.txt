cmake_minimum_required(VERSION 3.22)
project(mosh-pty C)

add_library(mosh-pty SHARED pty_jni.c)
target_link_libraries(mosh-pty log)
