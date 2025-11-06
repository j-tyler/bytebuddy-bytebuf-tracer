# ByteBuf Flow Tracker - Example Output

## Tree View Output

This is what you would see when calling the JMX `getTreeView()` operation:

```
ROOT: FrameDecoder.decode [count=45892]
├── MessageValidator.validate [ref=1, count=45892]
│   ├── MessageRouter.route [ref=2, count=43567]
│   │   ├── UserMessageHandler.handle [ref=2, count=21453]
│   │   │   ├── UserService.processMessage [ref=2, count=20234]
│   │   │   │   ├── MessageStore.persist [ref=1, count=20234]
│   │   │   │   │   └── ByteBuf.release [ref=0, count=20234]
│   │   │   └── UserService.processMessage [ref=1, count=1219]
│   │   │       └── MessageStore.persist [ref=0, count=1219]
│   │   ├── SystemMessageHandler.handle [ref=2, count=18234]
│   │   │   └── SystemLogger.log [ref=1, count=18234]
│   │   │       └── ByteBuf.release [ref=0, count=18234]
│   │   └── AdminMessageHandler.handle [ref=2, count=3880]
│   │       ├── AdminService.process [ref=1, count=3456]
│   │       │   └── ByteBuf.release [ref=0, count=3456]
│   │       └── AdminService.process [ref=2, count=424]
│   │           └── AuditLogger.log [ref=1, count=424] ⚠️ LEAK
│   └── ErrorHandler.handleInvalidMessage [ref=1, count=2325]
│       └── ErrorLogger.logError [ref=1, count=2325] ⚠️ LEAK

ROOT: HttpRequestDecoder.decode [count=23421]
├── HttpRequestHandler.handle [ref=1, count=23421]
│   ├── AuthenticationFilter.filter [ref=2, count=23421]
│   │   ├── RequestProcessor.process [ref=2, count=22156]
│   │   │   ├── ResponseWriter.write [ref=1, count=22156]
│   │   │   │   └── ChannelHandlerContext.writeAndFlush [ref=0, count=22156]
│   │   │   └── ResponseWriter.write [ref=0, count=22156]
│   │   └── AuthenticationFilter.reject [ref=1, count=1265]
│   │       └── ByteBuf.release [ref=0, count=1265]
│   └── HttpRequestHandler.handleException [ref=2, count=23421]
│       └── ByteBuf.release [ref=1, count=23421] ⚠️ LEAK

ROOT: WebSocketFrameDecoder.decode [count=8234]
└── WebSocketHandler.handleFrame [ref=1, count=8234]
    ├── PingHandler.handlePing [ref=1, count=4567]
    │   └── ByteBuf.release [ref=0, count=4567]
    ├── MessageHandler.handleMessage [ref=1, count=2345]
    │   ├── BroadcastService.broadcast [ref=1, count=1234]
    │   │   └── ByteBuf.release [ref=0, count=1234]
    │   └── BroadcastService.broadcast [ref=2, count=1111]
    │       └── ChannelGroup.writeAndFlush [ref=1, count=1111] ⚠️ LEAK
    └── CloseHandler.handleClose [ref=1, count=1322]
        └── ByteBuf.release [ref=0, count=1322]

ROOT: FileUploadDecoder.decode [count=1245]
├── FileUploadHandler.handle [ref=1, count=1245]
│   ├── FileValidator.validate [ref=2, count=1245]
│   │   ├── FileStorage.store [ref=1, count=1156]
│   │   │   └── ByteBuf.release [ref=0, count=1156]
│   │   └── FileValidator.reject [ref=2, count=89]
│   │       └── ByteBuf.release [ref=2, count=89] ⚠️ LEAK
│   └── FileUploadHandler.cleanup [ref=0, count=1245]
```

## Flat Path View Output

This is what you would see when calling the JMX `getFlatView()` operation:

```
[count=20234] FrameDecoder.decode -> MessageValidator.validate[1] -> MessageRouter.route[2] -> UserMessageHandler.handle[2] -> UserService.processMessage[2] -> MessageStore.persist[1] -> ByteBuf.release[0]

[count=1219] FrameDecoder.decode -> MessageValidator.validate[1] -> MessageRouter.route[2] -> UserMessageHandler.handle[2] -> UserService.processMessage[1] -> MessageStore.persist[0]

[count=18234] FrameDecoder.decode -> MessageValidator.validate[1] -> MessageRouter.route[2] -> SystemMessageHandler.handle[2] -> SystemLogger.log[1] -> ByteBuf.release[0]

[count=3456] FrameDecoder.decode -> MessageValidator.validate[1] -> MessageRouter.route[2] -> AdminMessageHandler.handle[2] -> AdminService.process[1] -> ByteBuf.release[0]

[count=424] [LEAK:ref=1] FrameDecoder.decode -> MessageValidator.validate[1] -> MessageRouter.route[2] -> AdminMessageHandler.handle[2] -> AdminService.process[2] -> AuditLogger.log[1]

[count=2325] [LEAK:ref=1] FrameDecoder.decode -> MessageValidator.validate[1] -> ErrorHandler.handleInvalidMessage[1] -> ErrorLogger.logError[1]

[count=22156] HttpRequestDecoder.decode -> HttpRequestHandler.handle[1] -> AuthenticationFilter.filter[2] -> RequestProcessor.process[2] -> ResponseWriter.write[1] -> ChannelHandlerContext.writeAndFlush[0]

[count=1265] HttpRequestDecoder.decode -> HttpRequestHandler.handle[1] -> AuthenticationFilter.filter[2] -> AuthenticationFilter.reject[1] -> ByteBuf.release[0]

[count=23421] [LEAK:ref=1] HttpRequestDecoder.decode -> HttpRequestHandler.handle[1] -> HttpRequestHandler.handleException[2] -> ByteBuf.release[1]

[count=1111] [LEAK:ref=1] WebSocketFrameDecoder.decode -> WebSocketHandler.handleFrame[1] -> MessageHandler.handleMessage[1] -> BroadcastService.broadcast[2] -> ChannelGroup.writeAndFlush[1]

[count=89] [LEAK:ref=2] FileUploadDecoder.decode -> FileUploadHandler.handle[1] -> FileValidator.validate[2] -> FileValidator.reject[2] -> ByteBuf.release[2]
```

## CSV Output

This is what you would see when calling the JMX `getCsvView()` operation:

```csv
root,path,final_ref_count,traversal_count,is_leak
"FrameDecoder.decode","FrameDecoder.decode[1] -> MessageValidator.validate[1] -> MessageRouter.route[2] -> UserMessageHandler.handle[2] -> UserService.processMessage[2] -> MessageStore.persist[1] -> ByteBuf.release[0]",0,20234,false
"FrameDecoder.decode","FrameDecoder.decode[1] -> MessageValidator.validate[1] -> MessageRouter.route[2] -> UserMessageHandler.handle[2] -> UserService.processMessage[1] -> MessageStore.persist[0]",0,1219,false
"FrameDecoder.decode","FrameDecoder.decode[1] -> MessageValidator.validate[1] -> MessageRouter.route[2] -> AdminMessageHandler.handle[2] -> AdminService.process[2] -> AuditLogger.log[1]",1,424,true
"FrameDecoder.decode","FrameDecoder.decode[1] -> MessageValidator.validate[1] -> ErrorHandler.handleInvalidMessage[1] -> ErrorLogger.logError[1]",1,2325,true
"HttpRequestDecoder.decode","HttpRequestDecoder.decode[1] -> HttpRequestHandler.handle[1] -> HttpRequestHandler.handleException[2] -> ByteBuf.release[1]",1,23421,true
"WebSocketFrameDecoder.decode","WebSocketFrameDecoder.decode[1] -> WebSocketHandler.handleFrame[1] -> MessageHandler.handleMessage[1] -> BroadcastService.broadcast[2] -> ChannelGroup.writeAndFlush[1]",1,1111,true
"FileUploadDecoder.decode","FileUploadDecoder.decode[1] -> FileUploadHandler.handle[1] -> FileValidator.validate[2] -> FileValidator.reject[2] -> ByteBuf.release[2]",2,89,true
```

## Summary Output

This is what you would see when calling the JMX `getSummary()` operation:

```
=== ByteBuf Flow Tracking Status ===
Time: Thu Nov 07 2024 14:23:45 GMT
Active Flows: 127
Root Methods: 4

=== ByteBuf Flow Summary ===
Total Root Methods: 4
Total Traversals: 78792
Unique Paths: 17
Leak Paths: 6
Leak Percentage: 35.29%

Top Leak Paths by Volume:
1. HttpRequestHandler.handleException: 23,421 leaked instances (29.7% of total)
2. ErrorLogger.logError: 2,325 leaked instances (2.9% of total)  
3. ChannelGroup.writeAndFlush: 1,111 leaked instances (1.4% of total)
4. AuditLogger.log: 424 leaked instances (0.5% of total)
5. FileValidator.reject: 89 leaked instances (0.1% of total)

Anomalies Detected:
- UserService.processMessage: Shows both ref=1 and ref=2 on same path
- BroadcastService.broadcast: Shows both ref=1 and ref=2 on same path
- ResponseWriter.write: Shows both ref=0 and ref=1 on same path
```

## What This Output Reveals

### Clear Leak Patterns
1. **HttpRequestHandler.handleException** - High volume leak (23,421 instances)
   - The ByteBuf.release is called but refCount only drops to 1, not 0
   - Suggests double-retain somewhere in the exception path

2. **ErrorLogger.logError** - Consistent leak (2,325 instances)  
   - Never calls release at all
   - Simple fix: add ByteBuf.release() after logging

3. **ChannelGroup.writeAndFlush** - Broadcast leak (1,111 instances)
   - Reference count is 2 when it should be 1
   - Likely retaining for broadcast but not releasing after

### Anomalies (Potential Bugs)
1. **UserService.processMessage** has two different paths with different refCounts
   - Sometimes ref=2, sometimes ref=1
   - Indicates inconsistent handling logic

2. **BroadcastService.broadcast** shows similar inconsistency
   - Could be conditional logic that doesn't always retain/release properly

### Hot Paths (Performance Insights)
- FrameDecoder.decode handles 45,892 ByteBufs (58% of total)
- Most traffic goes through UserMessageHandler (27% of total)
- FileUploadDecoder has lowest volume (1.5% of total)

This output format makes it trivial to:
- Identify leaks (non-zero refCount at leaves)
- Spot anomalies (different refCounts on same path)
- Find hot paths (high traversal counts)
- Prioritize fixes (sort by leak volume)
