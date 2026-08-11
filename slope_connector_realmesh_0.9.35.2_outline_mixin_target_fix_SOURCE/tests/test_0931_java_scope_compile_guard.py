#!/usr/bin/env python3
from pathlib import Path
ROOT=Path(__file__).parents[1]
service=(ROOT/'src/main/java/com/slopeconnector/model/ModelEndpointService.java').read_text()
# Do not rely on a negated instanceof pattern variable outside its if statement. Java's pattern
# variable is not definitely in scope on the fallback path, which previously broke compileJava.
assert 'ModelBlockEntity model;' in service
assert 'blockEntity instanceof ModelBlockEntity existing' in service
assert 'model = existing;' in service
assert 'model = new ModelBlockEntity(pos, state);' in service
assert 'if (!(blockEntity instanceof ModelBlockEntity model))' not in service
print('0.9.31 Java scope compile guard passed')
