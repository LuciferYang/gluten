/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "memory/MemoryAllocator.h"
#include <gtest/gtest.h>

using namespace gluten;

TEST(StdMemoryAllocator, allocateZeroFilledAccounting) {
  StdMemoryAllocator allocator;
  ASSERT_EQ(allocator.getBytes(), 0);

  // allocateZeroFilled with nmemb=10, size=64 should track 10*64=640 bytes.
  void* buf = nullptr;
  bool ok = allocator.allocateZeroFilled(10, 64, &buf);
  ASSERT_TRUE(ok);
  ASSERT_NE(buf, nullptr);
  ASSERT_EQ(allocator.getBytes(), 640);

  // Free should bring bytes back to zero.
  allocator.free(buf, 640);
  ASSERT_EQ(allocator.getBytes(), 0);
}

TEST(StdMemoryAllocator, allocateZeroFilledSingleElement) {
  StdMemoryAllocator allocator;

  // nmemb=1 case: should track exactly size bytes.
  void* buf = nullptr;
  bool ok = allocator.allocateZeroFilled(1, 128, &buf);
  ASSERT_TRUE(ok);
  ASSERT_EQ(allocator.getBytes(), 128);

  allocator.free(buf, 128);
  ASSERT_EQ(allocator.getBytes(), 0);
}

TEST(StdMemoryAllocator, allocateZeroFilledZeroSize) {
  StdMemoryAllocator allocator;

  // nmemb=0 or size=0: calloc returns non-null or null depending on impl,
  // but bytes tracked should be 0 regardless.
  void* buf = nullptr;
  bool ok = allocator.allocateZeroFilled(0, 64, &buf);
  if (ok) {
    ASSERT_EQ(allocator.getBytes(), 0);
    allocator.free(buf, 0);
  }
}

TEST(StdMemoryAllocator, allocateAndFreeAccounting) {
  StdMemoryAllocator allocator;

  void* buf1 = nullptr;
  void* buf2 = nullptr;
  bool ok1 = allocator.allocate(100, &buf1);
  ASSERT_TRUE(ok1);
  ASSERT_EQ(allocator.getBytes(), 100);

  bool ok2 = allocator.allocateZeroFilled(5, 200, &buf2);
  ASSERT_TRUE(ok2);
  ASSERT_EQ(allocator.getBytes(), 1100); // 100 + 5*200

  allocator.free(buf1, 100);
  ASSERT_EQ(allocator.getBytes(), 1000);

  allocator.free(buf2, 1000);
  ASSERT_EQ(allocator.getBytes(), 0);
}
