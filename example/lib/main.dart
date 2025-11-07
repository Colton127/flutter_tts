import 'dart:async';
import 'dart:io' show Platform;
import 'dart:math';

import 'package:flutter/foundation.dart' show kIsWeb;
import 'package:flutter/material.dart';
import 'package:flutter_tts/flutter_tts.dart';
import 'package:just_audio/just_audio.dart';
import 'package:path/path.dart' as p;
import 'package:path_provider/path_provider.dart';

class TTSVoice {
  final String locale;
  final String name;
  TTSVoice(this.locale, this.name);

  @override
  String toString() => 'TTSVoice{locale: $locale, name: $name}';
}

class TTSEngine {
  final String id;
  final String label;
  TTSEngine(this.id, this.label);

  @override
  String toString() => 'TTSEngine{id: $id, label: $label}';
}

void main() => runApp(MaterialApp(home: MyApp()));

class MyApp extends StatefulWidget {
  @override
  _MyAppState createState() => _MyAppState();
}

enum TtsState { playing, stopped, paused, continued }

class _MyAppState extends State<MyApp> {
  final r = Random();
  late final List<TTSVoice> ttsVoices;
  late FlutterTts flutterTts;
  final audioPlayer = AudioPlayer();
  late String ttsSynthOutputPath;
  final String ttsSynthOutputFileName = 'tts.caf';
  String? language;
  String? engine;
  double volume = 0.5;
  double pitch = 1.0;
  double rate = 0.5;
  bool isCurrentLanguageInstalled = false;

  String _newVoiceText = 'Hello, how are you? I am Flutter TTS. I am a text-to-speech plugin for Flutter. I support Web, Android, iOS, and Windows.';
  late final _textController = TextEditingController(text: _newVoiceText);
  int? _inputLength;

  TtsState ttsState = TtsState.stopped;

  bool get isPlaying => ttsState == TtsState.playing;
  bool get isStopped => ttsState == TtsState.stopped;
  bool get isPaused => ttsState == TtsState.paused;
  bool get isContinued => ttsState == TtsState.continued;

  bool get isIOS => !kIsWeb && Platform.isIOS;
  bool get isAndroid => !kIsWeb && Platform.isAndroid;
  bool get isWindows => !kIsWeb && Platform.isWindows;
  bool get isWeb => kIsWeb;

  Future<List<TTSVoice>> getTtsVoiceList() async {
    if (Platform.isAndroid) {
      final currentEngine = await flutterTts.getCurrentEngine;
      print('Current TTS Engine: $currentEngine');
    }

    final speechRateValidRange = await flutterTts.getSpeechRateValidRange;
    print('Speech Rate Valid Range: min: ${speechRateValidRange.min}, max: ${speechRateValidRange.max}, normal: ${speechRateValidRange.normal}');

    final List<TTSVoice> ttsVoiceList = [];
    final voiceData = await flutterTts.getVoices as List<dynamic>;
    for (final voice in voiceData) {
      if (!_isSupportediOSTtsVoice(voice['identifier'] as String?)) continue;
      final String? locale = voice['locale'] as String?;
      final String? name = voice['name'] as String?;
      final features = voice['features'] as String?;
      ttsVoiceList.add(TTSVoice(locale!, name!));
      print('TTSVoice: $locale, $name, $features');
    }
    print('Loaded ${ttsVoiceList.length} voices');
    return ttsVoiceList;
  }

  static bool _isSupportediOSTtsVoice(String? identifier) {
    if (identifier == null) return true;
    if (identifier.contains('speech.synthesis')) return false;
    if (identifier.contains('eloquence')) return false;
    return true;
  }

  @override
  initState() {
    super.initState();
    initTts();
  }

  dynamic initTts() {
    flutterTts = FlutterTts();

    _setAwaitOptions();
    if (isAndroid) {
      _getDefaultEngine();
      _getDefaultVoice();
    }

    flutterTts.setStartHandler(() {
      setState(() {
        print("Playing");
        ttsState = TtsState.playing;
      });
    });

    flutterTts.setCompletionHandler(() {
      setState(() {
        print("Complete");
        ttsState = TtsState.stopped;
      });
    });

    flutterTts.setCancelHandler(() {
      setState(() {
        print("Cancel");
        ttsState = TtsState.stopped;
      });
    });

    flutterTts.setPauseHandler(() {
      setState(() {
        print("Paused");
        ttsState = TtsState.paused;
      });
    });

    flutterTts.setContinueHandler(() {
      setState(() {
        print("Continued");
        ttsState = TtsState.continued;
      });
    });

    flutterTts.setErrorHandler((msg) {
      setState(() {
        print("error: $msg");
        ttsState = TtsState.stopped;
      });
    });
  }

  Future<dynamic> _getLanguages() async => await flutterTts.getLanguages;

  Future<List<TTSEngine>> _getEngines() async {
    final result = await flutterTts.getEngines as List<dynamic>;
    return result.map((e) => TTSEngine(e['name'] as String, e['label'] as String)).toList();
  }

  Future<void> _getDefaultEngine() async {
    var engine = await flutterTts.getDefaultEngine;
    if (engine != null) {
      print(engine);
    }
  }

  Future<void> _getDefaultVoice() async {
    var voice = await flutterTts.getDefaultVoice;
    if (voice != null) {
      print(voice);
    }
  }

  Future<void> _speak([String? text]) async {
    final result = await _synthesizeToFile(text ?? _newVoiceText);
    if (result != 1) {
      print('Error synthesizing text to file: $result');
      return;
    }

    await audioPlayer.setFilePath(ttsSynthOutputPath);
    await audioPlayer.play();
  }

  Future<void> _setAwaitOptions() async {
    ttsVoices = await getTtsVoiceList();
    final appDocDir = await getApplicationDocumentsDirectory();
    ttsSynthOutputPath = p.join(appDocDir.path, Platform.isIOS ? 'tts.caf' : 'tts.wav');
    await flutterTts.awaitSpeakCompletion(true);
    await flutterTts.awaitSynthCompletion(true);
  }

  Future<void> _stop() async {
    audioPlayer.pause();
    var result = await flutterTts.stop();
    if (result == 1) setState(() => ttsState = TtsState.stopped);
  }

  Future<void> _pause() async {
    getTtsVoiceList();

    audioPlayer.pause();
    var result = await flutterTts.pause();
    if (result == 1) setState(() => ttsState = TtsState.paused);
  }

  Future<dynamic> _synthesizeToFile(String text) async {
    await Future.wait([
      flutterTts.setVolume(volume),
      flutterTts.setSpeechRate(rate),
      flutterTts.setPitch(pitch),
    ]);

    return flutterTts.synthesizeToFile(text, ttsSynthOutputPath);
    // await audioPlayer.setFilePath(ttsSynthOutput);
    // await audioPlayer.play();
  }

  Future<void> _synthLoop() async {
    int iterations = 100;

    for (var i = 1; i <= iterations; i++) {
      _speak(i.toString());
    }

    // final stopwatch = Stopwatch()..start();
    // for (var i = 1; i <= iterations; i++) {
    //   int begin = stopwatch.elapsedMilliseconds;
    //   final voice = ttsVoices[i % ttsVoices.length];
    //   await flutterTts.setVoice({"name": voice.name, "locale": voice.locale});
    //   await _synthesizeToFile('h');
    //   print('synthLoop i $i / $iterations. Voice: $voice. Elapsed: ${stopwatch.elapsedMilliseconds - begin} ms');

    //   if (i % 10 == 0) {
    //     final average = stopwatch.elapsedMilliseconds / i;
    //     print('synthLoop i $i. Average: $average ms');
    //   }
    // }
    // stopwatch.stop();
  }

  Future<void> _randomizeVars() async {
    volume = r.nextDouble().clamp(0.1, 1.0);
    rate = r.nextDouble().clamp(0.1, 1.0);
    pitch = r.nextDouble() + 0.5;
    setState(() {});
  }

  @override
  void dispose() {
    super.dispose();
    flutterTts.stop();
  }

  // List<DropdownMenuItem<String>> getEnginesDropDownMenuItems(List<dynamic> engines) {
  //   var items = <DropdownMenuItem<String>>[];
  //   for (dynamic type in engines) {
  //     items.add(DropdownMenuItem(value: type as String?, child: Text((type as String))));
  //   }
  //   return items;
  // }
  List<DropdownMenuItem<String>> getEnginesDropDownMenuItems(List<TTSEngine> engines) {
    return engines
        .map((engine) => DropdownMenuItem(
              value: engine.id,
              child: Text(engine.label),
            ))
        .toList();
  }

  void changedEnginesDropDownItem(String? selectedEngine) async {
    await flutterTts.setEngine(selectedEngine!);
    language = null;
    setState(() {
      engine = selectedEngine;
    });
  }

  List<DropdownMenuItem<String>> getLanguageDropDownMenuItems(List<dynamic> languages) {
    var items = <DropdownMenuItem<String>>[];
    for (dynamic type in languages) {
      items.add(DropdownMenuItem(value: type as String?, child: Text((type as String))));
    }
    return items;
  }

  void changedLanguageDropDownItem(String? selectedType) {
    setState(() {
      language = selectedType;
      flutterTts.setLanguage(language!);
      if (isAndroid) {
        flutterTts.isLanguageInstalled(language!).then((value) => isCurrentLanguageInstalled = (value as bool));
      }
    });
  }

  void _onChange(String text) {
    setState(() {
      _newVoiceText = text;
    });
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text('Flutter TTS - MemleakFix'),
      ),
      body: SingleChildScrollView(
        scrollDirection: Axis.vertical,
        child: Column(
          children: [
            _inputSection(),
            _btnSection(),
            _engineSection(),
            _futureBuilder(),
            _buildSliders(),
            if (isAndroid) _getMaxSpeechInputLengthSection(),
            if (isAndroid) _setEngineLoop(),
            if (isAndroid) _setEngineOnce(),
            _rapidSynthCalls(),
          ],
        ),
      ),
    );
  }

  Widget _engineSection() {
    if (isAndroid) {
      return FutureBuilder<List<TTSEngine>>(
          future: _getEngines(),
          builder: (BuildContext context, snapshot) {
            if (snapshot.hasData) {
              return _enginesDropDownSection(snapshot.data!);
            } else if (snapshot.hasError) {
              return Text('Error loading engines: ${snapshot.error}');
            } else
              return Text('Loading engines...');
          });
    } else
      return Container(width: 0, height: 0);
  }

  Widget _futureBuilder() => FutureBuilder<dynamic>(
      future: _getLanguages(),
      builder: (BuildContext context, AsyncSnapshot<dynamic> snapshot) {
        if (snapshot.hasData) {
          return _languageDropDownSection(snapshot.data as List<dynamic>);
        } else if (snapshot.hasError) {
          return Text('Error loading languages...');
        } else
          return Text('Loading Languages...');
      });

  Widget _inputSection() => Container(
      alignment: Alignment.topCenter,
      padding: EdgeInsets.only(top: 25.0, left: 25.0, right: 25.0),
      child: TextField(
        controller: _textController,
        maxLines: 11,
        minLines: 6,
        onChanged: (String value) {
          _onChange(value);
        },
      ));

  Widget _btnSection() {
    return Container(
      padding: EdgeInsets.only(top: 50.0),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceEvenly,
        children: [
          _buildButtonColumn(Colors.purple, Colors.greenAccent, Icons.refresh, 'LOOP', _synthLoop),
          _buildButtonColumn(Colors.green, Colors.greenAccent, Icons.play_arrow, 'PLAY', _speak),
          _buildButtonColumn(Colors.red, Colors.redAccent, Icons.stop, 'STOP', _stop),
          _buildButtonColumn(Colors.blue, Colors.blueAccent, Icons.pause, 'PAUSE', _pause),
        ],
      ),
    );
  }

  Widget _enginesDropDownSection(List<TTSEngine> engines) => Container(
        padding: EdgeInsets.only(top: 50.0),
        child: DropdownButton(
          value: engine,
          items: getEnginesDropDownMenuItems(engines),
          onChanged: changedEnginesDropDownItem,
        ),
      );

  Widget _languageDropDownSection(List<dynamic> languages) => Container(
      padding: EdgeInsets.only(top: 10.0),
      child: Row(mainAxisAlignment: MainAxisAlignment.center, children: [
        DropdownButton(
          value: language,
          items: getLanguageDropDownMenuItems(languages),
          onChanged: changedLanguageDropDownItem,
        ),
        Visibility(
          visible: isAndroid,
          child: Text("Is installed: $isCurrentLanguageInstalled"),
        ),
      ]));

  Column _buildButtonColumn(Color color, Color splashColor, IconData icon, String label, Function func) {
    return Column(mainAxisSize: MainAxisSize.min, mainAxisAlignment: MainAxisAlignment.center, children: [
      IconButton(icon: Icon(icon), color: color, splashColor: splashColor, onPressed: () => func()),
      Container(margin: const EdgeInsets.only(top: 8.0), child: Text(label, style: TextStyle(fontSize: 12.0, fontWeight: FontWeight.w400, color: color)))
    ]);
  }

  Widget _getMaxSpeechInputLengthSection() {
    return Row(
      mainAxisAlignment: MainAxisAlignment.spaceEvenly,
      children: [
        ElevatedButton(
          child: Text('Get max speech input length'),
          onPressed: () async {
            _inputLength = await flutterTts.getMaxSpeechInputLength;
            setState(() {});
          },
        ),
        Text("$_inputLength characters"),
      ],
    );
  }

  Widget _setEngineLoop() {
    return ElevatedButton(
      child: Text('Set Engine Loop'),
      onPressed: () async {
        final engines = await _getEngines();
        for (int i = 0; i < 1000; i++) {
          final engine = engines[i % engines.length];
          flutterTts.setEngine(engine.id);
          print('Set engine to: ${engine.label}');
        }
      },
    );
  }

  Widget _setEngineOnce() {
    return ElevatedButton(
      child: Text('Set Engine Once'),
      onPressed: () async {
        try {
          final engine = (await _getEngines()).first;
          print('Setting engine to: ${engine.label}');
          await flutterTts.setEngine(engine.id);
          print('Engine set to: ${engine.label}');
        } catch (e) {
          print('Error setting engine: $e');
        }
      },
    );
  }

  Widget _rapidSynthCalls() {
    return ElevatedButton(
      child: Text('Rapid Synth Calls'),
      onPressed: () async {
        final voices = await getTtsVoiceList();
        // final engines = await _getEngines();
        for (int i = 0; i < 5000; i++) {
          //  final engine = engines[i % engines.length];
          // flutterTts.setEngine(engine.id);

          flutterTts.setVolume(volume);
          flutterTts.setSpeechRate(rate);
          flutterTts.setPitch(pitch);
          flutterTts.awaitSynthCompletion(true);

          flutterTts.getCurrentEngine;
          flutterTts.getDefaultEngine;
          flutterTts.getVoices;
          flutterTts.getEngines;

          final ttsVoice = voices[i % voices.length];
          flutterTts.setVoice({"name": ttsVoice.name, "locale": ttsVoice.locale});

          flutterTts.synthesizeToFile('', ttsSynthOutputPath);
        }
      },
    );
  }

  Widget _buildSliders() {
    return Column(
      children: [_volume(), _pitch(), _rate()],
    );
  }

  Widget _volume() {
    return Slider(
        value: volume,
        onChanged: (newVolume) {
          setState(() => volume = newVolume);
        },
        min: 0.0,
        max: 1.0,
        divisions: 10,
        label: "Volume: $volume");
  }

  Widget _pitch() {
    return Slider(
      value: pitch,
      onChanged: (newPitch) {
        setState(() => pitch = newPitch);
      },
      min: 0.5,
      max: 2.0,
      divisions: 15,
      label: "Pitch: $pitch",
      activeColor: Colors.red,
    );
  }

  Widget _rate() {
    return Slider(
      value: rate,
      onChanged: (newRate) {
        setState(() => rate = newRate);
      },
      min: 0.0,
      max: 1.0,
      divisions: 10,
      label: "Rate: $rate",
      activeColor: Colors.green,
    );
  }
}
