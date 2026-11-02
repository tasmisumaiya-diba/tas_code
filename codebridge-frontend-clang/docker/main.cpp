#include <clang-c/Index.h>
#include <nlohmann/json.hpp>
#include <iostream>
#include <map>
#include <string>
using nlohmann::json;

static std::string take(CXString value) {
  const char* text = clang_getCString(value);
  std::string result = text ? text : "";
  clang_disposeString(value);
  return result;
}
static json location(CXSourceLocation loc) {
  CXFile file = nullptr; unsigned line = 0, column = 0, offset = 0;
  clang_getExpansionLocation(loc, &file, &line, &column, &offset);
  if (!file || line == 0 || column == 0) return nullptr;
  return {{"line", line}, {"column", column}};
}
static json extent(CXCursor cursor) {
  CXSourceRange range = clang_getCursorExtent(cursor);
  json begin = location(clang_getRangeStart(range));
  json end = location(clang_getRangeEnd(range));
  if (begin.is_null()) return nullptr;
  if (!end.is_null()) { begin["endLine"] = end.value("line", 1u); begin["endColumn"] = end.value("column", 1u); }
  return begin;
}
static std::string kindName(CXCursor cursor) { return take(clang_getCursorKindSpelling(clang_getCursorKind(cursor))); }
static std::string usr(CXCursor cursor) { return take(clang_getCursorUSR(cursor)); }
static std::string spelling(CXType type) { return type.kind == CXType_Invalid ? "" : take(clang_getTypeSpelling(type)); }
static std::string linkage(CXCursor cursor) {
  switch (clang_getCursorLinkage(cursor)) {
    case CXLinkage_NoLinkage: return "none";
    case CXLinkage_Internal: return "internal";
    case CXLinkage_UniqueExternal: return "unique-external";
    case CXLinkage_External: return "external";
    default: return "invalid";
  }
}
static bool declaration(CXCursorKind kind) {
  switch (kind) {
    case CXCursor_FunctionDecl: case CXCursor_VarDecl: case CXCursor_ParmDecl: case CXCursor_FieldDecl:
    case CXCursor_StructDecl: case CXCursor_UnionDecl: case CXCursor_ClassDecl: case CXCursor_EnumDecl:
    case CXCursor_EnumConstantDecl: case CXCursor_TypedefDecl: case CXCursor_TypeAliasDecl:
    case CXCursor_CXXMethod: case CXCursor_Constructor: case CXCursor_Destructor: case CXCursor_Namespace:
      return true;
    default: return false;
  }
}
struct Context { CXFile mainFile; std::map<std::string, json> symbols; };
struct Children { json* values; Context* context; std::string scope; };
static json node(CXCursor cursor, const std::string& scope, Context& context, bool functionBody = false);
static CXChildVisitResult visitChild(CXCursor child, CXCursor parent, CXClientData data) {
  auto* children = static_cast<Children*>(data);
  CXCursorKind parentKind = clang_getCursorKind(parent);
  CXCursorKind childKind = clang_getCursorKind(child);
  bool functionBody = childKind == CXCursor_CompoundStmt && (parentKind == CXCursor_FunctionDecl || parentKind == CXCursor_CXXMethod || parentKind == CXCursor_Constructor || parentKind == CXCursor_Destructor);
  children->values->push_back(node(child, children->scope, *children->context, functionBody));
  return CXChildVisit_Continue;
}
static json node(CXCursor cursor, const std::string& scope, Context& context, bool functionBody) {
  const std::string kind = kindName(cursor), name = take(clang_getCursorSpelling(cursor));
  CXType type = clang_getCursorType(cursor), canonical = clang_getCanonicalType(type);
  const std::string id = usr(cursor);
  json result = {{"kind", kind}, {"name", name}, {"displayName", take(clang_getCursorDisplayName(cursor))},
    {"usr", id}, {"type", {{"spelling", spelling(type)}, {"canonical", spelling(canonical)}}},
    {"location", extent(cursor)}, {"attributes", json::object()}, {"children", json::array()}};
  result["attributes"]["definition"] = clang_isCursorDefinition(cursor) != 0;
  result["attributes"]["kindCode"] = static_cast<unsigned>(clang_getCursorKind(cursor));
  if (declaration(clang_getCursorKind(cursor)) && !id.empty()) {
    context.symbols[id] = {{"id", id}, {"kind", kind}, {"name", name}, {"scope", scope},
      {"type", spelling(type)}, {"canonicalType", spelling(canonical)},
      {"definition", clang_isCursorDefinition(cursor) != 0}, {"location", extent(cursor)}};
  }
  CXCursor ref = clang_getCursorReferenced(cursor);
  if (!clang_Cursor_isNull(ref)) result["referenced"] = {{"usr", usr(ref)}, {"name", take(clang_getCursorSpelling(ref))},
    {"kind", kindName(ref)}, {"type", spelling(clang_getCursorType(ref))}};
  std::string childScope = scope;
  const std::string cursorKind = kindName(cursor);
  if (declaration(clang_getCursorKind(cursor)) && !name.empty()) childScope = scope.empty() ? name : scope + "::" + name;
  else if ((cursorKind == "CompoundStmt" && !functionBody) || cursorKind == "ForStmt" || cursorKind == "CXXForRangeStmt" || cursorKind == "LambdaExpr") {
    json loc = location(clang_getCursorLocation(cursor));
    childScope += "::<" + cursorKind + "@" + std::to_string(loc.value("line", 1u)) + ":" + std::to_string(loc.value("column", 1u)) + ">";
  }
  result["attributes"]["typeSizeBytes"] = clang_Type_getSizeOf(type);
  result["attributes"]["typeAlignmentBytes"] = clang_Type_getAlignOf(type);
  result["attributes"]["constQualified"] = clang_isConstQualifiedType(type) != 0;
  result["attributes"]["volatileQualified"] = clang_isVolatileQualifiedType(type) != 0;
  if (clang_getCursorKind(cursor) == CXCursor_FunctionDecl || clang_getCursorKind(cursor) == CXCursor_CXXMethod) {
    result["attributes"]["linkage"] = linkage(cursor);
    result["attributes"]["inline"] = clang_Cursor_isFunctionInlined(cursor) != 0;
  }
  Children children{&result["children"], &context, childScope};
  clang_visitChildren(cursor, visitChild, &children);
  return result;
}
int main(int argc, char** argv) {
  if (argc != 4) { std::cerr << "usage: codebridge-clang-front <c|c++> <standard> <file>\n"; return 64; }
  const std::string language = argv[1], standard = argv[2], path = argv[3];
  const std::string standardFlag = "-std=" + standard;
  const char* arguments[] = {"-x", language.c_str(), standardFlag.c_str(), "-fsyntax-only", "-ferror-limit=100"};
  CXIndex index = clang_createIndex(0, 0);
  CXTranslationUnit unit = clang_parseTranslationUnit(index, path.c_str(), arguments, 5, nullptr, 0,
    CXTranslationUnit_DetailedPreprocessingRecord | CXTranslationUnit_KeepGoing);
  json declarations = json::array(), diagnostics = json::array();
  Context context{nullptr, {}};
  const std::string compilerVersion = take(clang_getClangVersion());
  std::string targetTriple;
  int pointerWidth = 0;
  if (unit) {
    context.mainFile = clang_getFile(unit, path.c_str());
    CXTargetInfo target = clang_getTranslationUnitTargetInfo(unit);
    if (target) { targetTriple = take(clang_TargetInfo_getTriple(target)); pointerWidth = clang_TargetInfo_getPointerWidth(target); clang_TargetInfo_dispose(target); }
    CXCursor tu = clang_getTranslationUnitCursor(unit);
    struct Top { CXFile file; Context* context; json* values; } top{context.mainFile, &context, &declarations};
    auto visitTop = [](CXCursor child, CXCursor, CXClientData data) {
      auto* top = static_cast<Top*>(data);
      CXFile file = nullptr; unsigned line = 0, column = 0, offset = 0;
      clang_getExpansionLocation(clang_getCursorLocation(child), &file, &line, &column, &offset);
      if (file && clang_File_isEqual(file, top->file)) top->values->push_back(node(child, "", *top->context));
      return CXChildVisit_Continue;
    };
    clang_visitChildren(tu, visitTop, &top);
    for (unsigned i = 0; i < clang_getNumDiagnostics(unit); ++i) {
      CXDiagnostic diagnostic = clang_getDiagnostic(unit, i);
      CXDiagnosticSeverity severity = clang_getDiagnosticSeverity(diagnostic);
      diagnostics.push_back({{"severity", severity >= CXDiagnostic_Error ? "error" : severity == CXDiagnostic_Warning ? "warning" : "info"},
        {"message", take(clang_formatDiagnostic(diagnostic, clang_defaultDiagnosticDisplayOptions()))},
        {"location", location(clang_getDiagnosticLocation(diagnostic))}});
      clang_disposeDiagnostic(diagnostic);
    }
  } else {
    diagnostics.push_back({{"severity", "error"}, {"message", "Clang could not construct a translation unit."}, {"location", nullptr}});
  }
  json symbols = json::array();
  for (const auto& entry : context.symbols) symbols.push_back(entry.second);
  std::cout << json{{"language", language == "c" ? "C" : "CPP"}, {"standard", standard}, {"compilerVersion", compilerVersion}, {"targetTriple", targetTriple}, {"pointerWidth", pointerWidth},
    {"declarations", declarations}, {"symbols", symbols}, {"diagnostics", diagnostics}}.dump();
  if (unit) clang_disposeTranslationUnit(unit);
  clang_disposeIndex(index);
  return 0;
}
