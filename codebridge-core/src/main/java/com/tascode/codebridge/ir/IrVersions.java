package com.tascode.codebridge.ir;
final class IrVersions {
 static final int CURRENT=1;
 private IrVersions(){}
 static void requireSupported(int version){if(version!=CURRENT)throw new IllegalArgumentException("Unsupported IR version: "+version);}
 static void requireMatching(int version,IrNode child){if(child.irVersion()!=version)throw new IllegalArgumentException("All nodes in an IR tree must use the same IR version");}
}
