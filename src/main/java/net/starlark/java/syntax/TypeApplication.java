// Copyright 2025 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package net.starlark.java.syntax;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

/** Syntax node for a type application expression. */
public final class TypeApplication extends Expression {

  private final Identifier constructor;
  private final ImmutableList<Expression> arguments;
  private final int rbracketOffset;

  TypeApplication(
      FileLocations locs,
      Identifier constructor,
      ImmutableList<Expression> arguments,
      int rbracketOffset) {
    super(locs, Kind.TYPE_APPLICATION);
    this.constructor = Preconditions.checkNotNull(constructor);
    this.arguments = arguments;
    this.rbracketOffset = rbracketOffset;
  }

  /** Returns the type constructor. */
  public Identifier getConstructor() {
    return this.constructor;
  }

  /** Returns the type arguments. */
  public ImmutableList<Expression> getArguments() {
    return arguments;
  }

  @Override
  public int getStartOffset() {
    return constructor.getStartOffset();
  }

  @Override
  public int getEndOffset() {
    return rbracketOffset + 1;
  }

  @Override
  public String toString() {
    StringBuilder buf = new StringBuilder();
    buf.append(constructor);
    buf.append('[');
    ListExpression.appendNodes(buf, arguments);
    buf.append(']');
    return buf.toString();
  }

  @Override
  public void accept(NodeVisitor visitor) {
    visitor.visit(this);
  }
}
